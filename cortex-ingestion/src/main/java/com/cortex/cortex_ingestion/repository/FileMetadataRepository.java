package com.cortex.cortex_ingestion.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cortex.cortex_ingestion.model.FileMetadata;
import java.util.List;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {
  Optional<FileMetadata> findByObjectName(String objectName);
}