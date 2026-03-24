package com.cortex.cortex_ingestion.service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.cortex.cortex_ingestion.dto.FileResponseDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FilesService {

  private final MediaChunkRepository mediaChunkRepository;

  private final FileMetadataRepository fileMetadataRepository;

  private final GcsStorageService gcsStorageService;

  public List<FileResponseDTO> getAllFiles() {
    try {
      List<FileResponseDTO> fileMetadataList = fileMetadataRepository.findAll().stream().map((FileMetadata file) -> {
        return FileResponseDTO.builder().fileId(file.getId()).fileDisplayName(file.getFileDisplayName())
            .objectName(file.getObjectName()).contentType(file.getContentType()).fileSize(file.getFileSize())
            .build();
      }).collect(Collectors.toList());

      return fileMetadataList;
    } catch (Exception e) {
      log.error("[FilesService] Error getting all files: {}", e.getMessage());
      throw new RuntimeException("Error getting all files", e);
    }
  }

  @Transactional
  public void deleteFile(UUID fileId) {
    try {
      FileMetadata metadata = fileMetadataRepository.findById(fileId).orElseThrow(() -> {
        log.error("[FileService] File Metadata not found for fileId: {}", fileId);
        throw new RuntimeException("File not found for fileId: " + fileId);
      });

      String objectName = metadata.getObjectName();

      mediaChunkRepository.deleteAllByFileId(fileId);
      fileMetadataRepository.delete(metadata);

      log.info("[FilesService] DB records for fileId: {} queued for deletion", fileId);

      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          try {
            gcsStorageService.deleteObject(objectName);
            log.info("[FilesService] Successfully deleted GCS objects for fileId: {}, objectName: {}", fileId,
                objectName);
          } catch (Exception e) {
            log.error("[FilesService] Failed to delete GCS objects for fileId: {}, objectName: {}", fileId,
                objectName, e);
          }
        }
      });

    } catch (Exception e) {
      log.error("[FilesService] Error deleting file: {}", e.getMessage());
      throw new RuntimeException("Error deleting file", e);
    }
  }

  @Transactional
  public void updateFileDisplayName(UUID fileId, String newDisplayName) {
    try {
      FileMetadata file = fileMetadataRepository.findById(fileId)
          .orElseThrow(() -> new RuntimeException("File not found for fileId: " + fileId));

      file.setFileDisplayName(newDisplayName);
      fileMetadataRepository.save(file);

      log.info("[FilesService] Successfully updated display name for fileId: {} to '{}'", fileId, newDisplayName);
    } catch (Exception e) {
      log.error("[FilesService] Error updating file display name: {}", e.getMessage());
      throw new RuntimeException("Error updating file display name", e);
    }
  }
}
