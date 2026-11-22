package com.cortex.cortex_ingestion.service;

import com.cortex.cortex_ingestion.repository.PipelineEventRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.cortex.cortex_ingestion.dto.FileResponseDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FilesService {

  private final PipelineEventRepository pipelineEventRepository;

  private final MediaChunkRepository mediaChunkRepository;

  private final FileMetadataRepository fileMetadataRepository;

  private final GcsStorageService gcsStorageService;

  public List<FileResponseDTO> getAllFiles(String userId) {
    return fileMetadataRepository.findAllByUserId(userId).stream()
        .map((FileMetadata file) -> {
          return FileResponseDTO.builder().fileId(file.getId()).fileDisplayName(file.getFileDisplayName())
              .objectName(file.getObjectName()).contentType(file.getContentType()).fileSize(file.getFileSize())
              .durationSeconds(file.getDurationSeconds())
              // FOR_CLEANUP is the sweep's to-do list; to the user, a failed file is FAILED.
              .fileStatus(file.getFileStatus().isFailed() ? FileStatusEnum.FAILED.name() : file.getFileStatus().name())
              .rejectionReason(file.getRejectionReason() != null ? file.getRejectionReason().name() : null)
              .playbackStatus(file.getPlaybackStatus() != null ? file.getPlaybackStatus().name() : null)
              .build();
        }).collect(Collectors.toList());
  }

  @Transactional
  public void deleteFile(UUID fileId, String userId) {
    FileMetadata metadata = fileMetadataRepository.findByIdAndUserId(fileId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));

    String objectName = metadata.getObjectName();

    mediaChunkRepository.deleteAllByFileId(fileId);
    fileMetadataRepository.delete(metadata);
    pipelineEventRepository.deleteAllByFileId(fileId);

    log.info("[FilesService] DB records for fileId: {} queued for deletion", fileId);

    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        try {
          gcsStorageService.deleteObject(objectName, fileId);
          log.info("[FilesService] Successfully deleted GCS objects for fileId: {}, objectName: {}", fileId,
              objectName);
        } catch (Exception e) { // After commit: the rows are gone, so this can only be logged; the objects stay.
          log.error("[FilesService] Failed to delete GCS objects for fileId: {}, objectName: {}", fileId,
              objectName, e);
        }
      }
    });
  }

  @Transactional
  public void updateFileDisplayName(UUID fileId, String newDisplayName, String userId) {
    if (fileMetadataRepository.rename(fileId, userId, newDisplayName) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
    }

    log.info("[FilesService] Successfully updated display name for fileId: {} to '{}'", fileId, newDisplayName);
  }

  /**
   * Rule 2's last step, after the sweep deleted the file's objects: FOR_CLEANUP becomes FAILED and
   * its media_chunk rows go, in one transaction. The row and its events stay (failed-states). False
   * when the file was no longer FOR_CLEANUP: nothing is changed.
   */
  @Transactional
  public boolean markCleaned(UUID fileId) {
    if (fileMetadataRepository.markCleaned(fileId) == 0) {
      return false; // no longer FOR_CLEANUP: deleted meanwhile, or already cleaned
    }
    mediaChunkRepository.deleteAllByFileId(fileId);
    return true;
  }

  /**
   * Rule 3's last step, after the sweep deleted the file's objects: every row of a deleted file, in
   * one transaction. False when it was not FOR_DELETION (already removed): nothing is changed.
   */
  @Transactional
  public boolean deleteRowsOfDeletedFile(UUID fileId) {
    if (fileMetadataRepository.deleteIfForDeletion(fileId) == 0) {
      return false; // already removed by an earlier run
    }
    mediaChunkRepository.deleteAllByFileId(fileId);
    pipelineEventRepository.deleteAllByFileId(fileId);
    return true;
  }
}
