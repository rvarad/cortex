package com.cortex.cortex_media_processing_service.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cortex.cortex_common.dto.ChunkUploadedEventDTO;
import com.cortex.cortex_media_processing_service.model.MediaChunk;
import com.cortex.cortex_media_processing_service.repository.MediaChunkRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
// @RequiredArgsConstructor
public class EmbeddingService {

  private final EmbeddingModel embeddingModel;
  private final MediaChunkRepository mediaChunkRepository;

  public EmbeddingService(@Qualifier("googleGenAiTextEmbedding") EmbeddingModel embeddingModel,
      MediaChunkRepository mediaChunkRepository) {
    this.embeddingModel = embeddingModel;
    this.mediaChunkRepository = mediaChunkRepository;
  }

  /**
   * Generates embedding for the combined content and updates the existing
   * MediaChunk.
   */
  @Transactional
  public void generateAndSaveEmbedding(ChunkUploadedEventDTO event, String visualSummary, String transcription) {
    try {
      log.info("Generating embedding for chunk index: {}", event.getChunkIndex());

      String combinedText = String.format("[VISUAL]: %s\n[TRANSCRIPT]: %s", visualSummary, transcription);

      // Generate embedding using Gemini (Spring AI Model)
      float[] vector = embeddingModel.embed(combinedText);

      // Update MediaChunk entity
      MediaChunk chunk = mediaChunkRepository.findById(event.getChunkId())
          .orElseThrow(() -> new RuntimeException("MediaChunk not found: " + event.getChunkId()));

      chunk.setVisualSummary(visualSummary);
      chunk.setTranscript(transcription);
      chunk.setEmbedding(vector);
      chunk.setStatus(MediaChunk.Status.COMPLETED);

      mediaChunkRepository.save(chunk);

      log.info("Successfully updated MediaChunk with embeddings for chunk: {}", event.getChunkId());
    } catch (Exception e) {
      log.error("Failed to update MediaChunk for chunk: {}", event.getChunkId(), e);
      throw new RuntimeException("Embedding storage failed", e);
    }
  }
}
