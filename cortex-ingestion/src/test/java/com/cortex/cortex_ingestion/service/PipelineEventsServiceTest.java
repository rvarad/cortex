package com.cortex.cortex_ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

  private final PipelineEventsService service = new PipelineEventsService(mock(SseEmitterRegistry.class),
      mock(PipelineEventRepository.class), fileMetadataRepository, mediaChunkRepository, objectMapper);

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
}
