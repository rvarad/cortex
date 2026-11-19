package com.cortex.cortex_ingestion.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cortex.cortex_ingestion.model.PipelineEvent;

@Repository
public interface PipelineEventRepository extends JpaRepository<PipelineEvent, UUID> {

  List<PipelineEvent> findByFileIdOrderByCreatedAtAsc(UUID fileId);

  void deleteAllByFileId(UUID fileId);

  /**
   * The sweep's rule 1: files still in the pipeline with no activity since the cut-off for their
   * status. Activity is the newest event, or the row's created_at when it has none (failed-states).
   * The cut-offs come from the JVM's clock, which wrote both created_at columns.
   */
  @Query(value = """
      SELECT f.id FROM file_metadata f
      LEFT JOIN LATERAL (SELECT max(e.created_at) AS last FROM pipeline_events e WHERE e.file_id = f.id) a ON true
      WHERE (f.file_status = 'UPLOADED' AND COALESCE(a.last, f.created_at) < :uploadedBefore)
         OR (f.file_status IN ('CHUNKED', 'PROCESSING') AND COALESCE(a.last, f.created_at) < :processingBefore)
      """, nativeQuery = true)
  List<UUID> findSilentFiles(@Param("uploadedBefore") LocalDateTime uploadedBefore,
      @Param("processingBefore") LocalDateTime processingBefore);

  /**
   * The sweep's rule 2: failed files with no activity since the cut-off, so nothing still writes
   * their data. Activity as in {@link #findSilentFiles}.
   */
  @Query(value = """
      SELECT f.id FROM file_metadata f
      LEFT JOIN LATERAL (SELECT max(e.created_at) AS last FROM pipeline_events e WHERE e.file_id = f.id) a ON true
      WHERE f.file_status = 'FOR_CLEANUP' AND COALESCE(a.last, f.created_at) < :before
      """, nativeQuery = true)
  List<UUID> findSilentFailedFiles(@Param("before") LocalDateTime before);
}
