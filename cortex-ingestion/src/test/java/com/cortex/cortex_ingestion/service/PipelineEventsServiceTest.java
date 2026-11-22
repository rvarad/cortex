package com.cortex.cortex_ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.model.MediaChunk;
import com.cortex.cortex_common.model.PipelineEventEnum;
import com.cortex.cortex_common.model.PlaybackStatusEnum;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import com.cortex.cortex_ingestion.model.PipelineEvent;
import com.cortex.cortex_ingestion.repository.PipelineEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Storing a pipeline event, and the completion check that some events run.
 *
 * <p>A pipeline event that can't be stored fails the record, so the Kafka handler reports it once
 * with its fileId (ADR-0014); the service doesn't log it and carry on. That failure is faked: the
 * real mapper can't fail to write a Map it has just read from Kafka. The test proves the path, not
 * that it happens.
 *
 * <p>The completion check never moves a terminal file: a failed file stays failed, even when all
 * its work looks done.
 */
class PipelineEventsServiceTest {

  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

  private final ObjectMapper objectMapper = mock(ObjectMapper.class);

  private final FileMetadataRepository fileMetadataRepository = mock(FileMetadataRepository.class);

  private final MediaChunkRepository mediaChunkRepository = mock(MediaChunkRepository.class);

  private final SseEmitterRegistry sseEmitterRegistry = mock(SseEmitterRegistry.class);

  private final PipelineEventRepository pipelineEventRepository = mock(PipelineEventRepository.class);

  private final PipelineEventsService service = new PipelineEventsService(sseEmitterRegistry,
      pipelineEventRepository, fileMetadataRepository, mediaChunkRepository, objectMapper);

  @BeforeEach
  void setUp() {
    captured.start();
    cortexLogger.addAppender(captured);
    // processNewEvent registers an after-commit hook, which needs an active transaction's
    // synchronization. No real transaction here: nothing commits, so the hook never runs.
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void tearDown() {
    TransactionSynchronizationManager.clearSynchronization();
    cortexLogger.detachAppender(captured);
  }

  @Test
  void anEventThatCantBeStored_failsTheRecord_andLogsNothing() throws Exception {
    JsonProcessingException unwritable = new JsonProcessingException("cannot write") {
    };
    when(objectMapper.writeValueAsString(any())).thenThrow(unwritable);

    assertThatThrownBy(() -> service.processNewEvent(PipelineEventDTO.builder()
        .fileId(UUID.randomUUID())
        .eventType(PipelineEventEnum.CHUNK_UPLOAD_STARTED)
        .message("test")
        .metadata(Map.of("mediaType", "video"))
        .build()))
        .hasCause(unwritable);
    assertThat(captured.list).as("ERROR lines").noneMatch(line -> line.getLevel() == Level.ERROR);
  }

  @Test
  void anEmbeddingCompleteForAForCleanupFile_leavesItAsItIs() {
    // ---- Arrange: failed, but every chunk is embedded and playback is ready: without the
    // terminal check, this file would become COMPLETED ----
    UUID fileId = UUID.randomUUID();
    FileMetadata file = FileMetadata.builder()
        .id(fileId)
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName("uploads/media/" + fileId + ".mp4")
        .fileSize(1_000L)
        .fileStatus(FileStatusEnum.FOR_CLEANUP)
        .contentType("video/mp4")
        .userId("user-1")
        .totalChunks(3)
        .playbackStatus(PlaybackStatusEnum.READY)
        .build();
    when(fileMetadataRepository.findByIdForUpdate(fileId)).thenReturn(Optional.of(file));
    when(mediaChunkRepository.countByFileIdAndStatus(fileId, MediaChunk.Status.COMPLETED)).thenReturn(3L);

    // ---- Act ----
    service.processNewEvent(PipelineEventDTO.builder()
        .fileId(fileId)
        .eventType(PipelineEventEnum.EMBEDDING_COMPLETE)
        .message("Embedding complete")
        .build());

    // ---- Assert ----
    assertThat(file.getFileStatus()).isEqualTo(FileStatusEnum.FOR_CLEANUP);
    verify(fileMetadataRepository, never()).save(any());
  }

  @Test
  void anEventForAMissingFile_logsNoWarning() {
    // Deleted while its job still runs (FOR_DELETION is hidden): normal, so INFO at most (brick 13).
    service.processNewEvent(PipelineEventDTO.builder()
        .fileId(UUID.randomUUID())
        .eventType(PipelineEventEnum.EMBEDDING_COMPLETE)
        .message("Embedding complete")
        .build());

    assertThat(captured.list).as("lines above INFO")
        .noneMatch(line -> line.getLevel().isGreaterOrEqual(Level.WARN));
  }

  // ---- Rule 1 (failed-states, brick 11) ----

  @Test
  void failIfStuck_onAFileWithChunksLeft_marksIt_andSavesOnePipelineFailed() {
    UUID fileId = UUID.randomUUID();
    when(fileMetadataRepository.findByIdForUpdate(fileId))
        .thenReturn(Optional.of(file(fileId, FileStatusEnum.PROCESSING, 3)));
    when(mediaChunkRepository.countByFileIdAndStatus(fileId, MediaChunk.Status.COMPLETED)).thenReturn(2L);
    when(fileMetadataRepository.markForCleanup(fileId, FileStatusEnum.TERMINAL_STATUSES)).thenReturn(1);

    service.failIfStuck(fileId);

    verify(fileMetadataRepository).markForCleanup(fileId, FileStatusEnum.TERMINAL_STATUSES);
    assertThat(savedEvents()).singleElement().satisfies(event -> {
      assertThat(event.getEventType()).isEqualTo(PipelineEventEnum.PIPELINE_FAILED);
      assertThat(event.getMessage()).isEqualTo(PipelineEventEnum.FAILED_BY_NO_PROGRESS_MESSAGE);
    });
  }

  @Test
  void failIfStuck_onAnUploadedFile_marksIt_andSavesOnePipelineFailed() {
    // No totalChunks until chunking completes: the completion check can never complete it.
    UUID fileId = UUID.randomUUID();
    when(fileMetadataRepository.findByIdForUpdate(fileId))
        .thenReturn(Optional.of(file(fileId, FileStatusEnum.UPLOADED, null)));
    when(fileMetadataRepository.markForCleanup(fileId, FileStatusEnum.TERMINAL_STATUSES)).thenReturn(1);

    service.failIfStuck(fileId);

    verify(fileMetadataRepository).markForCleanup(fileId, FileStatusEnum.TERMINAL_STATUSES);
    assertThat(savedEvents()).extracting(PipelineEvent::getEventType).containsExactly(PipelineEventEnum.PIPELINE_FAILED);
  }

  @Test
  void failIfStuck_onAFileWhoseWorkIsAllSaved_completesIt_insteadOfFailingIt() {
    // Every chunk embedded and playback ready, but the last completion trigger was lost.
    UUID fileId = UUID.randomUUID();
    FileMetadata file = file(fileId, FileStatusEnum.PROCESSING, 3);
    when(fileMetadataRepository.findByIdForUpdate(fileId)).thenReturn(Optional.of(file));
    when(mediaChunkRepository.countByFileIdAndStatus(fileId, MediaChunk.Status.COMPLETED)).thenReturn(3L);

    service.failIfStuck(fileId);

    assertThat(file.getFileStatus()).isEqualTo(FileStatusEnum.COMPLETED);
    assertThat(savedEvents()).extracting(PipelineEvent::getEventType).containsExactly(PipelineEventEnum.PIPELINE_COMPLETE);
    verify(fileMetadataRepository, never()).markForCleanup(any(), any());
  }

  @Test
  void endOpenStreamsOfEndedFiles_endsAFailedFilesStream_andLeavesAProcessingOne() {
    UUID failed = UUID.randomUUID();
    UUID processing = UUID.randomUUID();
    when(sseEmitterRegistry.openFileIds()).thenReturn(Set.of(failed, processing));
    when(fileMetadataRepository.findById(failed)).thenReturn(Optional.of(file(failed, FileStatusEnum.FOR_CLEANUP, 3)));
    when(fileMetadataRepository.findById(processing))
        .thenReturn(Optional.of(file(processing, FileStatusEnum.PROCESSING, 3)));

    service.endOpenStreamsOfEndedFiles();

    ArgumentCaptor<PipelineEventDTO> sent = ArgumentCaptor.forClass(PipelineEventDTO.class);
    verify(sseEmitterRegistry).broadcast(sent.capture());
    assertThat(sent.getValue().getFileId()).isEqualTo(failed);
    assertThat(sent.getValue().getEventType()).isEqualTo(PipelineEventEnum.PIPELINE_FAILED);
    verify(sseEmitterRegistry).completeEmitters(failed);
    verify(sseEmitterRegistry, never()).completeEmitters(processing);
  }

  private static FileMetadata file(UUID fileId, FileStatusEnum status, Integer totalChunks) {
    return FileMetadata.builder()
        .id(fileId)
        .fileDisplayName("talk.mp4")
        .bucketName("cortex-dev-bucket")
        .objectName("uploads/media/" + fileId + ".mp4")
        .fileSize(1_000L)
        .fileStatus(status)
        .contentType("video/mp4")
        .userId("user-1")
        .totalChunks(totalChunks)
        .playbackStatus(PlaybackStatusEnum.READY)
        .build();
  }

  /** Every event the service stored, in order; none is fine, unlike a captor's verify. */
  private List<PipelineEvent> savedEvents() {
    return mockingDetails(pipelineEventRepository).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("save"))
        .map(invocation -> (PipelineEvent) invocation.getArgument(0))
        .toList();
  }
}
