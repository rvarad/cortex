package com.cortex.cortex_ingestion.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cortex.cortex_ingestion.model.PipelineEvent;

@Repository
public interface PipelineEventRepository extends JpaRepository<PipelineEvent, UUID> {

  List<PipelineEvent> findByFileIdOrderByCreatedAtAsc(UUID fileId);
}
