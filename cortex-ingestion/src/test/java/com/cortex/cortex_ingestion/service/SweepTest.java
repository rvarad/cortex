package com.cortex.cortex_ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_ingestion.repository.PipelineEventRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * One run of the sweep (failed-states, brick 11): each status is judged by its own silence, a file
 * that cannot be judged is logged once and does not stop the others, and open pages are checked last.
 */
class SweepTest {

  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  /** Freezes each event as it arrives — logback reads MDC lazily, from whoever asks first. */
  private final ListAppender<ILoggingEvent> captured = new ListAppender<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };

  private final PipelineEventRepository pipelineEventRepository = mock(PipelineEventRepository.class);

  private final PipelineEventsService pipelineEventsService = mock(PipelineEventsService.class);

  private final FileMetadataRepository fileMetadataRepository = mock(FileMetadataRepository.class);

  private final GcsStorageService gcsStorageService = mock(GcsStorageService.class);

  private final FilesService filesService = mock(FilesService.class);

  private final Sweep sweep = new Sweep(pipelineEventRepository, pipelineEventsService, fileMetadataRepository,
      gcsStorageService, filesService, Duration.ofHours(2), Duration.ofMinutes(45), Duration.ofHours(2));

  @BeforeEach
  void setUp() {
    captured.start();
    cortexLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    cortexLogger.detachAppender(captured);
  }

  @Test
  void eachStatusIsJudgedByItsOwnSilence() {
    sweep.run();

    ArgumentCaptor<LocalDateTime> uploadedBefore = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> processingBefore = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(pipelineEventRepository).findSilentFiles(uploadedBefore.capture(), processingBefore.capture());
    LocalDateTime now = LocalDateTime.now();
    assertThat(uploadedBefore.getValue()).as("UPLOADED cut-off").isCloseTo(now.minusHours(2), within(1, ChronoUnit.MINUTES));
    assertThat(processingBefore.getValue()).as("CHUNKED/PROCESSING cut-off")
        .isCloseTo(now.minusMinutes(45), within(1, ChronoUnit.MINUTES));
  }

  @Test
  void aFileThatCannotBeJudged_isLoggedOnceWithItsFileId_andTheNextIsStillJudged() {
    UUID broken = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    when(pipelineEventRepository.findSilentFiles(any(), any())).thenReturn(List.of(broken, next));
    doThrow(new IllegalStateException("database down")).when(pipelineEventsService).failIfStuck(broken);

    sweep.run();

    InOrder order = inOrder(pipelineEventsService);
    order.verify(pipelineEventsService).failIfStuck(broken);
    order.verify(pipelineEventsService).failIfStuck(next);
    order.verify(pipelineEventsService).endOpenStreamsOfEndedFiles();

    List<ILoggingEvent> errors = captured.list.stream().filter(line -> line.getLevel() == Level.ERROR).toList();
    assertThat(errors).as("ERROR lines").hasSize(1);
    assertThat(errors.get(0).getMDCPropertyMap()).containsEntry("fileId", broken.toString());
    assertThat(errors.get(0).getThrowableProxy().getMessage()).isEqualTo("database down");
  }

  // ---- Abandoned uploads (failed-states, brick 11b) ----

  @Test
  void anAbandonedUpload_isDeletedRowFirst_thenItsObject() {
    FileMetadata pending = pendingFile();
    when(fileMetadataRepository.findByFileStatusAndCreatedAtBefore(eq(FileStatusEnum.PENDING), any()))
        .thenReturn(List.of(pending));
    when(fileMetadataRepository.deleteIfPending(pending.getId())).thenReturn(1);

    sweep.run();

    ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(fileMetadataRepository).findByFileStatusAndCreatedAtBefore(eq(FileStatusEnum.PENDING), before.capture());
    assertThat(before.getValue()).as("PENDING cut-off")
        .isCloseTo(LocalDateTime.now().minusHours(2), within(1, ChronoUnit.MINUTES));

    InOrder order = inOrder(fileMetadataRepository, gcsStorageService);
    order.verify(fileMetadataRepository).deleteIfPending(pending.getId());
    order.verify(gcsStorageService).deleteObject(pending.getObjectName(), pending.getId());
  }

  @Test
  void anUploadThatLandedMeanwhile_isLeftAlone() {
    // The webhook marked it UPLOADED between the query and the delete: no row deleted, no object either.
    FileMetadata pending = pendingFile();
    when(fileMetadataRepository.findByFileStatusAndCreatedAtBefore(eq(FileStatusEnum.PENDING), any()))
        .thenReturn(List.of(pending));
    when(fileMetadataRepository.deleteIfPending(pending.getId())).thenReturn(0);

    sweep.run();

    verify(fileMetadataRepository).deleteIfPending(pending.getId());
    verify(gcsStorageService, never()).deleteObject(any(), any());
  }

  // ---- Rule 2 (failed-states, brick 12) ----

  @Test
  void aSilentFailedFile_losesItsObjectsFirst_thenItsChunks() {
    FileMetadata failed = givenASilentFailedFile();
    when(filesService.markCleaned(failed.getId())).thenReturn(true);

    sweep.run();

    verify(pipelineEventRepository).findSilentFailedFiles(any());
    InOrder order = inOrder(gcsStorageService, filesService);
    order.verify(gcsStorageService).deleteObject(failed.getObjectName(), failed.getId());
    order.verify(filesService).markCleaned(failed.getId());
  }

  @Test
  void aGcsDeleteThatThrows_leavesTheFileForTheNextRun() {
    FileMetadata failed = givenASilentFailedFile();
    doThrow(new IllegalStateException("GCS unreachable"))
        .doNothing()
        .when(gcsStorageService).deleteObject(failed.getObjectName(), failed.getId());

    sweep.run();

    verify(filesService, never()).markCleaned(any());
    List<ILoggingEvent> errors = captured.list.stream().filter(line -> line.getLevel() == Level.ERROR).toList();
    assertThat(errors).as("ERROR lines").hasSize(1);
    assertThat(errors.get(0).getMDCPropertyMap()).containsEntry("fileId", failed.getId().toString());

    sweep.run(); // still FOR_CLEANUP, so picked again; GCS works now

    verify(filesService).markCleaned(failed.getId());
  }

  private FileMetadata givenASilentFailedFile() {
    FileMetadata failed = file(FileStatusEnum.FOR_CLEANUP);
    when(pipelineEventRepository.findSilentFailedFiles(any())).thenReturn(List.of(failed.getId()));
    when(fileMetadataRepository.findById(failed.getId())).thenReturn(Optional.of(failed));
    return failed;
  }

  private static FileMetadata pendingFile() {
    return file(FileStatusEnum.PENDING);
  }

  private static FileMetadata file(FileStatusEnum status) {
    UUID fileId = UUID.randomUUID();
    return FileMetadata.builder()
        .id(fileId)
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName("uploads/media/" + fileId + ".mp4")
        .fileSize(1_000L)
        .fileStatus(status)
        .contentType("video/mp4")
        .userId("user-1")
        .build();
  }
}
