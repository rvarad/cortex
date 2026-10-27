package com.cortex.cortex_ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cortex.cortex_common.dto.PipelineEventDTO;
import com.cortex.cortex_common.model.PipelineEventEnum;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A user closing a file's page while it processes is normal: the next event's send to that page
 * fails. ADR-0014's SSE rule: that is logged at INFO and stops the sending, it is not an error.
 */
class SseEmitterRegistryTest {

  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

  private final SseEmitterRegistry registry = new SseEmitterRegistry();

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
  void aPageThatWasClosed_isAnInfoLine_notAnError_andGetsNoMoreEvents() {
    UUID fileId = UUID.randomUUID();
    AtomicInteger sends = new AtomicInteger();
    SseEmitter closedPage = new SseEmitter(0L) {
      @Override
      public void send(SseEventBuilder builder) throws IOException {
        sends.incrementAndGet();
        throw new IOException("Broken pipe");
      }
    };
    registry.addEmitter(fileId, closedPage);

    registry.broadcast(event(fileId, PipelineEventEnum.CHUNK_UPLOAD_STARTED));
    registry.broadcast(event(fileId, PipelineEventEnum.CHUNK_UPLOAD_COMPLETE));

    assertThat(sends).as("sends to the closed page: removed after the first failure").hasValue(1);
    assertThat(linesAt(Level.ERROR)).as("ERROR lines").isEmpty();
    List<ILoggingEvent> infos = linesAt(Level.INFO);
    assertThat(infos).as("INFO lines").hasSize(1);
    assertThat(infos.get(0).getThrowableProxy()).as("no stack trace").isNull();
  }

  @Test
  void aPageThatWasClosed_failingToComplete_isAnInfoLine_notAnError() {
    // complete() fails when the page has already gone: the same normal case as a failed send.
    UUID fileId = UUID.randomUUID();
    SseEmitter closedPage = new SseEmitter(0L) {
      @Override
      public void complete() {
        throw new IllegalStateException("ResponseBodyEmitter has already completed");
      }
    };
    registry.addEmitter(fileId, closedPage);

    registry.completeEmitters(fileId);

    assertThat(linesAt(Level.ERROR)).as("ERROR lines").isEmpty();
    assertThat(linesAt(Level.INFO)).as("INFO lines")
        .anySatisfy(line -> assertThat(line.getThrowableProxy()).as("no stack trace").isNull());
  }

  private static PipelineEventDTO event(UUID fileId, PipelineEventEnum type) {
    return PipelineEventDTO.builder().fileId(fileId).eventType(type).message("test").build();
  }

  private List<ILoggingEvent> linesAt(Level level) {
    return captured.list.stream().filter(line -> line.getLevel() == level).toList();
  }
}
