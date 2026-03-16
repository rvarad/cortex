package com.cortex.cortex_rag_orchestration.service;

import java.util.UUID;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.model.MediaChunk;
import com.cortex.cortex_common.repository.MediaChunkRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
// @RequiredArgsConstructor
public class EmbeddingService {

  private final EmbeddingModel embeddingModel;

  private final MediaChunkRepository mediaChunkRepository;

  public EmbeddingService(
      @Qualifier("googleGenAiTextEmbedding") EmbeddingModel embeddingModel,
      MediaChunkRepository mediaChunkRepository) {
    this.embeddingModel = embeddingModel;
    this.mediaChunkRepository = mediaChunkRepository;
  }

  public void generateAndSaveEmbedding(String visualSummary, String transcript, UUID chunkId, int chunkIndex) {
    try {
      log.info("Generating embedding for chunkId: {}, chunkIndex: {}", chunkId, chunkIndex);

      String combinedText = String.format("[VISUAL]: %s\n[TRANSCRIPT]: %s\n", visualSummary, transcript);

      float[] vector = embeddingModel.embed(combinedText);

      MediaChunk chunk = mediaChunkRepository.findById(chunkId)
          .orElseThrow(() -> new RuntimeException("MediaChunk not found: " + chunkId));

      chunk.setVisualSummary(visualSummary);
      chunk.setTranscript(transcript);
      chunk.setEmbedding(vector);
      chunk.setStatus(MediaChunk.Status.COMPLETED);

      mediaChunkRepository.save(chunk);

      log.info("Successfully updated MediaChunk with embeddings for chunk: {}, chunkIndex: {}", chunkId, chunkIndex);
    } catch (Exception e) {
      log.error("Failed to update MediaChunk for chunk: {}, chunkIndex: {}", chunkId, chunkIndex, e);
      throw new RuntimeException("Failed to update MediaChunk for chunk: " + chunkId, e);
    }
  }

}
