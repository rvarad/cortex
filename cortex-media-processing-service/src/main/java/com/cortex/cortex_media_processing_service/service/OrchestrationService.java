package com.cortex.cortex_media_processing_service.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class OrchestrationService {

  private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

  private final VisionService visionService;
  private final TranscriptionService transcriptionService;
  private final EmbeddingService embeddingService;

  @KafkaListener(topics = "media-chunk-uploaded", groupId = "cortex-media-processing-group", concurrency = "3", properties = {
      "max.poll.records=1",
      "max.poll.interval.ms=300000"
  })
  public CompletableFuture<Void> handlefileUploadEvent(ChunkUploadedEventDTO event) {
    try {
      log.info("Processing chunk: {}/{} for video: {}", event.getChunkIndex(), event.getObjectName(),
          event.getVideoPath());

      CompletableFuture<String> visionFuture = CompletableFuture
          .supplyAsync(() -> visionService.describeVideoChunk(event.getVideoPath()), executorService);

      CompletableFuture<String> transcriptionFuture = CompletableFuture
          .supplyAsync(() -> transcriptionService.transcribe(event.getAudioPath()), executorService);

      CompletableFuture.allOf(visionFuture, transcriptionFuture).join();

      String description = visionFuture.get();
      String transcription = transcriptionFuture.get();

      log.info("Vision Description: {}", description);
      log.info("Audio Transcription: {}", transcription);

      log.info("Updating MediaChunk with results for chunk: {}", event.getChunkId());
      embeddingService.generateAndSaveEmbedding(event, description, transcription);

    } catch (Exception e) {
      log.error("Error handling file upload event for object: {}", event.getObjectName(), e);
    }
    return CompletableFuture.completedFuture(null);
  }
}
