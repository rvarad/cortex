package com.cortex.cortex_ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.PipelineEventEnum;
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
 * A pipeline event that can't be stored fails the record, so the Kafka handler reports it once
 * with its fileId (ADR-0014); the service doesn't log it and carry on.
 *
 * <p>The failure is faked: the real mapper can't fail to write a Map it has just read from Kafka.
 * The test proves the path, not that it happens.
 */
class PipelineEventsServiceTest {

  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

  private final ObjectMapper objectMapper = mock(ObjectMapper.class);

  private final PipelineEventsService service = new PipelineEventsService(mock(SseEmitterRegistry.class),
      mock(PipelineEventRepository.class), mock(FileMetadataRepository.class), mock(MediaChunkRepository.class),
      objectMapper);

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
}
