package com.cortex.cortex_common.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cortex.cortex_common.model.MediaChunk;

@Repository
public interface MediaChunkRepository extends JpaRepository<MediaChunk, UUID> {

  @Query(value = """
      SELECT id FROM media_chunk
      WHERE status = 'COMPLETED'
      AND embedding IS NOT NULL
      AND user_id = :userId
      AND (:fileId IS NULL OR file_id = :fileId)
      ORDER BY embedding <=> cast(:queryVector as vector)
      LIMIT :maxResults
      """, nativeQuery = true)
  List<UUID> semanticSearch(
      @Param("userId") String userId,
      @Param("queryVector") float[] queryVector,
      @Param("fileId") UUID fileId,
      @Param("maxResults") int maxResults);

  @Query(value = """
      SELECT id FROM media_chunk
      WHERE status = 'COMPLETED'
      AND user_id = :userId
      AND (:fileId IS NULL OR file_id = :fileId)
      AND search_vector @@ websearch_to_tsquery(get_pg_dictionary(:langCode), :queryText)
      ORDER BY ts_rank(
          search_vector,
          websearch_to_tsquery(get_pg_dictionary(:langCode), :queryText)
      ) DESC
      LIMIT :maxResults
          """, nativeQuery = true)
  List<UUID> lexicalSearch(
      @Param("userId") String userId,
      @Param("queryText") String queryText,
      @Param("langCode") String langCode,
      @Param("fileId") UUID fileId,
      @Param("maxResults") int maxResults);

  void deleteAllByFileId(UUID fileId);

  long countByFileId(UUID fileId);

  long countByFileIdAndStatus(UUID fileId, MediaChunk.Status status);

  List<MediaChunk> findByFileIdAndUserIdAndStatusOrderByChunkIndexAsc(UUID fileId, String userId,
      MediaChunk.Status status);
}
