package com.cortex.cortex_common.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cortex.cortex_common.model.FileMetadata;

import jakarta.persistence.LockModeType;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {
  Optional<FileMetadata> findByObjectName(String objectName);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT fm FROM FileMetadata AS fm WHERE fm.id = :fileId")
  Optional<FileMetadata> findByIdForUpdate(@Param("fileId") UUID fileId);
}
