package com.cortex.cortex_ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.cortex.cortex_ingestion.dto.FileResponseDTO;
import com.cortex.cortex_ingestion.repository.PipelineEventRepository;

/**
 * FOR_CLEANUP is the sweep's to-do list, not something the user needs to know: the file list shows
 * it as FAILED, so the frontend never sees it.
 */
class FilesServiceTest {

  private final FileMetadataRepository fileMetadataRepository = mock(FileMetadataRepository.class);

  private final FilesService filesService = new FilesService(mock(PipelineEventRepository.class),
      mock(MediaChunkRepository.class), fileMetadataRepository, mock(GcsStorageService.class));

  @Test
  void aForCleanupFile_isListedAsFailed() {
    UUID fileId = UUID.randomUUID();
    when(fileMetadataRepository.findAllByUserId("user-1")).thenReturn(List.of(FileMetadata.builder()
        .id(fileId)
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName("uploads/media/" + fileId + ".mp4")
        .fileSize(1_000L)
        .fileStatus(FileStatusEnum.FOR_CLEANUP)
        .contentType("video/mp4")
        .userId("user-1")
        .build()));

    List<FileResponseDTO> files = filesService.getAllFiles("user-1");

    assertThat(files).singleElement().extracting(FileResponseDTO::getFileStatus).isEqualTo("FAILED");
  }
}
