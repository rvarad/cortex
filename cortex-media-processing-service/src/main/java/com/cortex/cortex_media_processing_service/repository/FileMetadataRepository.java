package com.cortex.cortex_media_processing_service.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cortex.cortex_common.model.FileMetadata;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {
  Optional<FileMetadata> findByObjectName(String objectName);
}
