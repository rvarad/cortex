package com.cortex.cortex_rag_orchestration.service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.kafka.KafkaSends;
import com.cortex.cortex_common.model.PipelineEventEnum;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestrationService {

  private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

  @Value("${app.kafka.topic.pipeline-events}")
  private String pipelineEventsTopic;

  private final KafkaTemplate<String, Object> kafkaTemplate;

  private final VisionService visionService;
  private final TranscriptionService transcriptionService;
  private final EmbeddingService embeddingService;

  private int INFERENCE_TIMEOUT_SECONDS = 200;
  private int EMBED_TIMEOUT_SECONDS = 45;

  @KafkaListener(topics = "media-chunk-uploaded", groupId = "${spring.kafka.consumer.group-id}", concurrency = "3", properties = {
      "max.poll.records=1",
      "max.poll.interval.ms=480000" // 8 minutes
  })
  public void processMediaChunk(ChunkUploadedEventDTO event) {

    MDC.put("fileId", event.getFileId().toString());
    try {
      if (event.getVideoPath() != null && !event.getVideoPath().isEmpty()) {
        sendGlassBoxEvent(event.getFileId().toString(),
            PipelineEventDTO.builder().fileId(event.getFileId()).chunkId(event.getChunkId())
                .chunkIndex(event.getChunkIndex())
                .eventType(PipelineEventEnum.VISION_ANALYSIS_STARTED)
                .message("Vision analysis started").metadata(Map.of("chunkId", event.getChunkId())).build());
      }

      CompletableFuture<String> visionFuture = event.getVideoPath() != null && !event.getVideoPath().isEmpty()
          ? CompletableFuture.supplyAsync(() -> {
            try {
              MDC.put("fileId", event.getFileId().toString());
              String visionDescription = visionService.generateDescription(event.getVideoPath());
              sendGlassBoxEvent(event.getFileId().toString(),
                  PipelineEventDTO.builder().fileId(event.getFileId()).chunkId(event.getChunkId())
                      .chunkIndex(event.getChunkIndex())
                      .eventType(PipelineEventEnum.VISION_ANALYSIS_COMPLETE)
                      .message("Vision analysis complete").metadata(Map.of("visionDescription", visionDescription))
                      .build());
              return visionDescription;
            } finally {
              MDC.remove("fileId");
            }
          }, executorService)
          : CompletableFuture.completedFuture("");

      if (event.getAudioPath() != null && !event.getAudioPath().isEmpty()) {
        sendGlassBoxEvent(event.getFileId().toString(),
            PipelineEventDTO.builder().fileId(event.getFileId()).chunkId(event.getChunkId())
                .chunkIndex(event.getChunkIndex())
                .eventType(PipelineEventEnum.TRANSCRIPTION_STARTED)
                .message("Transcription started").metadata(Map.of("chunkId", event.getChunkId())).build());
      }

      CompletableFuture<TranscriptionService.TranscriptionResult> transcriptionFuture = event.getAudioPath() != null
          && !event.getAudioPath().isEmpty()
              ? CompletableFuture.supplyAsync(() -> {
                try {
                  MDC.put("fileId", event.getFileId().toString());
                  TranscriptionService.TranscriptionResult transcriptionResult = transcriptionService
                      .transcribe(event.getAudioPath());
                  sendGlassBoxEvent(event.getFileId().toString(),
                      PipelineEventDTO.builder().fileId(event.getFileId()).chunkId(event.getChunkId())
                          .chunkIndex(event.getChunkIndex())
                          .eventType(PipelineEventEnum.TRANSCRIPTION_COMPLETE)
                          .message("Transcription complete").metadata(Map.of("transcript",
                              transcriptionResult.transcript(), "languageCode", transcriptionResult.languageCode()))
                          .build());
                  return transcriptionResult;
                } finally {
                  MDC.remove("fileId");
                }
              }, executorService)
              : CompletableFuture.completedFuture(new TranscriptionService.TranscriptionResult("", null));

      CompletableFuture.allOf(visionFuture, transcriptionFuture).get(INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      String visionDescription = visionFuture.get();
      TranscriptionService.TranscriptionResult transcriptionResult = transcriptionFuture.get();

      log.info("Vision Description: {}", visionDescription);
      log.info("Audio Transcription: {}", transcriptionResult.transcript());
      log.info("Detected Language: {}", transcriptionResult.languageCode());

      CompletableFuture
          .runAsync(() -> {
            try {
              MDC.put("fileId", event.getFileId().toString());
              embeddingService.generateAndSaveEmbedding(visionDescription, transcriptionResult.transcript(),
                  event.getChunkId(),
                  event.getChunkIndex(), transcriptionResult.languageCode());
            } finally {
              MDC.remove("fileId");
            }
          }, executorService)
          .get(EMBED_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      sendPipelineEvent(event.getFileId().toString(),
          PipelineEventDTO.builder().fileId(event.getFileId()).chunkId(event.getChunkId())
              .chunkIndex(event.getChunkIndex())
              .eventType(PipelineEventEnum.EMBEDDING_COMPLETE).message("Embedding complete")
              .build());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Error processing media chunk", e);
    } catch (Exception e) {
      throw new RuntimeException("Error processing media chunk", e);
    } finally {
      MDC.remove("fileId");
    }
  }

  /**
   * Waits for the broker: a failed send throws here and fails the record
   * (ADR-0014).
   */
  private void sendPipelineEvent(String key, PipelineEventDTO event) {
    KafkaSends.await(kafkaTemplate.send(pipelineEventsTopic, key, event));
  }

  /** A glass box event: it only feeds the timeline, so a failed send is a WARN (ADR-0014, 2026-10-30). */
  private void sendGlassBoxEvent(String key, PipelineEventDTO event) {
    try {
      sendPipelineEvent(key, event);
    } catch (RuntimeException e) { // Not rethrown: the timeline loses one event; the chunk's work must go on.
      log.warn("Glass box event {} not sent", event.getEventType(), e);
    }
  }
}
