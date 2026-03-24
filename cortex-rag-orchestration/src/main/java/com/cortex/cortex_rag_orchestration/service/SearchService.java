package com.cortex.cortex_rag_orchestration.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.cortex.cortex_common.dto.SearchRequestDTO;
import com.cortex.cortex_common.dto.SearchResultDTO;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.MediaChunk;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SearchService {

  private final EmbeddingModel embeddingModel;

  private final MediaChunkRepository mediaChunkRepository;

  private final FileMetadataRepository fileMetadataRepository;

  private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

  private static final int CANDIDATE_LIMIT = 100;

  private static final double RRF_K = 60;

  public SearchService(@Qualifier("googleGenAiTextEmbedding") EmbeddingModel embeddingModel,
      MediaChunkRepository mediaChunkRepository, FileMetadataRepository fileMetadataRepository) {
    this.embeddingModel = embeddingModel;
    this.mediaChunkRepository = mediaChunkRepository;
    this.fileMetadataRepository = fileMetadataRepository;
  }

  public List<SearchResultDTO> search(SearchRequestDTO request) {

    float[] queryVector = embeddingModel.embed(request.getQuery());

    String languageCode = request.getLanguageCode() != null ? request.getLanguageCode() : "en";

    CompletableFuture<List<UUID>> semanticSearchFuture = CompletableFuture.supplyAsync(
        () -> mediaChunkRepository.semanticSearch(queryVector, request.getFileId(), CANDIDATE_LIMIT), executorService);

    CompletableFuture<List<UUID>> lexicalSearchFuture = CompletableFuture.supplyAsync(() -> mediaChunkRepository
        .lexicalSearch(request.getQuery(), languageCode, request.getFileId(), CANDIDATE_LIMIT), executorService);

    CompletableFuture.allOf(semanticSearchFuture, lexicalSearchFuture).join();

    List<UUID> semanticIds = semanticSearchFuture.join();
    List<UUID> lexicalIds = lexicalSearchFuture.join();

    Map<UUID, Double> scores = calculateRRFScore(semanticIds, lexicalIds);

    List<UUID> sortedScores = scores.entrySet().stream().sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
        .limit(request.getMaxResults()).map(Map.Entry::getKey).toList();

    Map<UUID, MediaChunk> chunks = mediaChunkRepository.findAllById(sortedScores).stream()
        .collect(Collectors.toMap(MediaChunk::getId, chunk -> chunk));

    // Fetch FileMetadata for file names
    List<UUID> uniqueFileIds = chunks.values().stream()
        .map(MediaChunk::getFileId)
        .distinct()
        .toList();

    Map<UUID, String> fileNames = fileMetadataRepository.findAllById(uniqueFileIds).stream()
        .collect(Collectors.toMap(FileMetadata::getId, FileMetadata::getFileDisplayName));

    List<SearchResultDTO> results = new ArrayList<>();

    for (UUID id : sortedScores) {
      MediaChunk chunk = chunks.get(id);

      if (chunk != null) {
        String displayName = fileNames.getOrDefault(chunk.getFileId(), "Unknown File");

        results.add(
            SearchResultDTO.builder().id(id).fileId(chunk.getFileId())
                .fileDisplayName(displayName)
                .chunkIndex(chunk.getChunkIndex()).startTime(chunk.getStartTime()).endTime(chunk.getEndTime())
                .transcript(chunk.getTranscript()).visualSummary(chunk.getVisualSummary())
                .languageCode(chunk.getLanguageCode()).score(scores.get(id)).build());
      }
    }

    return results;

  }

  private Map<UUID, Double> calculateRRFScore(List<UUID> semanticIds, List<UUID> lexicalIds) {
    Map<UUID, Double> scores = new HashMap<>();

    for (int i = 0; i < semanticIds.size(); i++) {
      UUID id = semanticIds.get(i);
      int rank = i + 1;
      scores.merge(id, 1.0 / (RRF_K + rank), Double::sum);
    }

    for (int i = 0; i < lexicalIds.size(); i++) {
      UUID id = lexicalIds.get(i);
      int rank = i + 1;
      scores.merge(id, 1.0 / (RRF_K + rank), Double::sum);
    }

    return scores;
  }
}
