package com.cortex.cortex_ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.cortex.cortex_ingestion.dto.FileResponseDTO;
import com.cortex.cortex_ingestion.repository.PipelineEventRepository;

/**
 * FOR_CLEANUP is the sweep's to-do list, not something the user needs to know: the file list shows
 * it as FAILED, so the frontend never sees it. Delete only marks the file (failed-states, brick 14).
 */
class FilesServiceTest {

  private final FileMetadataRepository fileMetadataRepository = mock(FileMetadataRepository.class);

  private final PipelineEventRepository pipelineEventRepository = mock(PipelineEventRepository.class);

  private final MediaChunkRepository mediaChunkRepository = mock(MediaChunkRepository.class);

  private final FilesService filesService = new FilesService(pipelineEventRepository,
      mediaChunkRepository, fileMetadataRepository);

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

  @Test
  void deleteFile_marksTheFile_andDeletesNoRowsAndNoObjects() {
    // Brick 14: Delete only marks; rule 3 removes the objects, then the rows, once nothing writes them.
    UUID fileId = UUID.randomUUID();
    when(fileMetadataRepository.markForDeletion(fileId, "user-1")).thenReturn(1);

    filesService.deleteFile(fileId, "user-1");

    verify(fileMetadataRepository).markForDeletion(fileId, "user-1");
    verifyNoMoreFileWrites();
    verifyNoInteractions(mediaChunkRepository, pipelineEventRepository);
  }

  @Test
  void deleteFile_ofAFileThatIsGone_is404() {
    UUID fileId = UUID.randomUUID();
    when(fileMetadataRepository.markForDeletion(fileId, "user-1")).thenReturn(0);

    assertThatThrownBy(() -> filesService.deleteFile(fileId, "user-1"))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    verifyNoInteractions(mediaChunkRepository, pipelineEventRepository);
  }

  private void verifyNoMoreFileWrites() {
    verify(fileMetadataRepository, never()).delete(any(FileMetadata.class));
    verify(fileMetadataRepository, never()).deleteIfForDeletion(any());
  }
}
