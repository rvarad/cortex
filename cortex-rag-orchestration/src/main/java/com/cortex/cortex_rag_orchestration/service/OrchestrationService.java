package com.cortex.cortex_rag_orchestration.service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;
import com.cortex.cortex_common.dto.PipelineEventDTO;
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

  @KafkaListener(topics = "media-chunk-uploaded", groupId = "${spring.kafka.consumer.group-id}", concurrency = "3", properties = {
      "max.poll.records=1",
      "max.poll.interval.ms=300000"
  })
  public CompletableFuture<Void> processMediaChunk(ChunkUploadedEventDTO event) {

    try {

      if (event.getVideoPath() != null && !event.getVideoPath().isEmpty()) {
        kafkaTemplate.send(pipelineEventsTopic, event.getFileId().toString(),
            PipelineEventDTO.builder().fileId(event.getFileId()).chunkId(event.getChunkId())
                .chunkIndex(event.getChunkIndex())
                .eventType(PipelineEventEnum.VISION_ANALYSIS_STARTED)
                .message("Vision analysis started").metadata(Map.of("chunkId", event.getChunkId())).build());
      }

      CompletableFuture<String> visionFuture = event.getVideoPath() != null && !event.getVideoPath().isEmpty()
          ? CompletableFuture.supplyAsync(() -> {
            String visionDescription = visionService.generateDescription(event.getVideoPath());
            kafkaTemplate.send(pipelineEventsTopic, event.getFileId().toString(),
                PipelineEventDTO.builder().fileId(event.getFileId()).chunkId(event.getChunkId())
                    .chunkIndex(event.getChunkIndex())
                    .eventType(PipelineEventEnum.VISION_ANALYSIS_COMPLETE)
                    .message("Vision analysis complete").metadata(Map.of("visionDescription", visionDescription))
                    .build());
            return visionDescription;
          }, executorService)
          : CompletableFuture.completedFuture("");

      if (event.getAudioPath() != null && !event.getAudioPath().isEmpty()) {
        kafkaTemplate.send(pipelineEventsTopic, event.getFileId().toString(),
            PipelineEventDTO.builder().fileId(event.getFileId()).chunkId(event.getChunkId())
                .chunkIndex(event.getChunkIndex())
                .eventType(PipelineEventEnum.TRANSCRIPTION_STARTED)
                .message("Transcription started").metadata(Map.of("chunkId", event.getChunkId())).build());
      }

      CompletableFuture<TranscriptionService.TranscriptionResult> transcriptionFuture = event.getAudioPath() != null
          && !event.getAudioPath().isEmpty()
              ? CompletableFuture.supplyAsync(() -> {
                TranscriptionService.TranscriptionResult transcriptionResult = transcriptionService
                    .transcribe(event.getAudioPath());
                kafkaTemplate.send(pipelineEventsTopic, event.getFileId().toString(),
                    PipelineEventDTO.builder().fileId(event.getFileId()).chunkId(event.getChunkId())
                        .chunkIndex(event.getChunkIndex())
                        .eventType(PipelineEventEnum.TRANSCRIPTION_COMPLETE)
                        .message("Transcription complete").metadata(Map.of("transcript",
                            transcriptionResult.transcript(), "languageCode", transcriptionResult.languageCode()))
                        .build());
                return transcriptionResult;
              }, executorService)
              : CompletableFuture.completedFuture(new TranscriptionService.TranscriptionResult("", null));

      CompletableFuture.allOf(visionFuture, transcriptionFuture).join();

      String visionDescription = visionFuture.get();
      TranscriptionService.TranscriptionResult transcriptionResult = transcriptionFuture.get();

      log.info("Vision Description: {}", visionDescription);
      log.info("Audio Transcription: {}", transcriptionResult.transcript());
      log.info("Detected Language: {}", transcriptionResult.languageCode());

      embeddingService.generateAndSaveEmbedding(visionDescription, transcriptionResult.transcript(), event.getChunkId(),
          event.getChunkIndex(), transcriptionResult.languageCode());

      kafkaTemplate.send(pipelineEventsTopic, event.getFileId().toString(),
          PipelineEventDTO.builder().fileId(event.getFileId()).chunkId(event.getChunkId())
              .chunkIndex(event.getChunkIndex())
              .eventType(PipelineEventEnum.EMBEDDING_COMPLETE).message("Embedding complete")
              .build());
    } catch (Exception e) {
      log.error("Error processing media chunk for object: {}", event.getObjectName(), e);
    }
    return CompletableFuture.completedFuture(null);
  }
}
