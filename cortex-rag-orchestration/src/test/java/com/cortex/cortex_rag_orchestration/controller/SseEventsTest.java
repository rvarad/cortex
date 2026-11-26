package com.cortex.cortex_rag_orchestration.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cortex.cortex_common.utils.BackgroundTask;
import com.cortex.cortex_rag_orchestration.service.StepEvent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * {@link SseEvents#answering} is the boundary for every answer stream. Three ways an answer ends,
 * and what each must do to the stream and to the log:
 *
 * <ul>
 *   <li>finished — the stream is completed, nothing is logged as an error;
 *   <li>failed — the stream is closed with the error, and the boundary logs it once;
 *   <li>the client left — expected: one INFO line, no ERROR, nothing more done to the stream.
 * </ul>
 *
 * <p>The task is run on the test's own thread. BackgroundTask behaves the same there (its own tests
 * cover that), and it keeps these tests free of waiting.
 */
class SseEventsTest {

  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  private final List<ILoggingEvent> captured = new CopyOnWriteArrayList<>();

  private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      captured.add(event);
    }
  };

  @BeforeEach
  void attachAppender() {
    appender.start();
    cortexLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    cortexLogger.detachAppender(appender);
  }

  @Test
  void finishedAnswer_completesTheStream_andLogsNoError() {
    RecordingEmitter emitter = new RecordingEmitter(false);

    SseEvents.answering(emitter, "conversation answer", () -> {
      SseEvents.send(emitter, new StepEvent("Searching library…"));
      SseEvents.done(emitter);
    }).run();

    assertThat(emitter.sends).isEqualTo(2);
    assertThat(emitter.completed).isTrue();
    assertThat(emitter.completedWithError).isNull();
    assertThat(errors()).isEmpty();
  }

  @Test
  void failedAnswer_closesTheStreamWithTheError_andIsLoggedOnceAtTheBoundary() {
    RecordingEmitter emitter = new RecordingEmitter(false);
    IllegalStateException failure = new IllegalStateException("model unavailable");

    SseEvents.answering(emitter, "conversation answer", () -> {
      throw failure;
    }).run();

    // The stream is closed with the error, so the client stops waiting; it is not completed as if
    // the answer had finished.
    assertThat(emitter.completedWithError).isSameAs(failure);
    assertThat(emitter.completed).isFalse();

    List<ILoggingEvent> errors = errors();
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).getLoggerName()).isEqualTo(BackgroundTask.class.getName());
    assertThat(errors.get(0).getThrowableProxy().getMessage()).isEqualTo("model unavailable");
  }

  @Test
  void clientThatLeft_isLoggedAtInfo_notAsAnError() {
    // The connection is gone: every send fails the way the servlet container reports it.
    RecordingEmitter emitter = new RecordingEmitter(true);

    SseEvents.answering(emitter, "conversation answer", () -> {
      SseEvents.send(emitter, new StepEvent("Searching library…"));
      SseEvents.done(emitter); // never reached: the first send stops the answer
    }).run();

    assertThat(errors()).isEmpty();

    List<ILoggingEvent> infos = captured.stream()
        .filter(event -> event.getLevel() == Level.INFO)
        .filter(event -> event.getLoggerName().equals(SseEvents.class.getName()))
        .toList();
    assertThat(infos).hasSize(1);
    assertThat(infos.get(0).getFormattedMessage()).contains("client disconnected");

    // Nothing more is done to a stream whose client is gone.
    assertThat(emitter.sends).isEqualTo(1);
    assertThat(emitter.completedWithError).isNull();
    assertThat(emitter.completed).isFalse();
  }

  private List<ILoggingEvent> errors() {
    return captured.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
  }

  /**
   * An SseEmitter with no HTTP connection behind it, recording what is done to it. With
   * {@code clientGone}, every send throws the IOException a servlet container throws for a closed
   * connection.
   */
  private static final class RecordingEmitter extends SseEmitter {

    private final boolean clientGone;

    private int sends;

    private boolean completed;

    private Throwable completedWithError;

    RecordingEmitter(boolean clientGone) {
      this.clientGone = clientGone;
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      sends++;
      if (clientGone) {
        throw new IOException("Broken pipe");
      }
    }

    @Override
    public synchronized void complete() {
      completed = true;
    }

    @Override
    public synchronized void completeWithError(Throwable ex) {
      completedWithError = ex;
    }
  }
}
