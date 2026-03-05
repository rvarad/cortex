package com.cortex.rag_orchestration.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestrationService {

  private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

  private final VisionService visionService;
  private final TranscriptionService transcriptionService;
  private final EmbeddingService embeddingService;

  @KafkaListener(topics = "media-chunk-uploaded", groupId = "${spring.kafka.consumer.group-id}", concurrency = "3", properties = {
      "max.poll.records=1",
      "max.poll.interval.ms=300000"
  })
  public CompletableFuture<Void> processMediaChunk(ChunkUploadedEventDTO event) {

    try {
      CompletableFuture<String> visionFuture = event.getVideoPath() != null && !event.getVideoPath().isEmpty()
          ? CompletableFuture.supplyAsync(() -> visionService.generateDescription(event.getVideoPath()),
              executorService)
          : CompletableFuture.completedFuture("");

      CompletableFuture<String> transcriptionFuture = event.getAudioPath() != null && !event.getAudioPath().isEmpty()
          ? CompletableFuture.supplyAsync(() -> transcriptionService.transcribe(event.getAudioPath()),
              executorService)
          : CompletableFuture.completedFuture("");

      CompletableFuture.allOf(visionFuture, transcriptionFuture).join();

      String visionDescription = visionFuture.get();
      String transcription = transcriptionFuture.get();

      log.info("Vision Description: {}", visionDescription);
      log.info("Audio Transcription: {}", transcription);

      embeddingService.generateAndSaveEmbedding(visionDescription, transcription, event.getChunkId(),
          event.getChunkIndex());
    } catch (Exception e) {
      log.error("Error processing media chunk for object: {}", event.getObjectName(), e);
    }
    return CompletableFuture.completedFuture(null);
  }
}
