package com.cortex.cortex_common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * BackgroundTask is the boundary for fire-and-forget threads (ADR-0014), so these tests check the
 * boundary's whole contract: a failure becomes exactly one ERROR line carrying the stack trace and
 * the caller's ids, nothing escapes onto the thread, and the thread's MDC is left as it was found.
 *
 * <p>Log lines are checked as captured logback events, not as printed text: the event has the
 * level, the exception and the MDC as fields, whatever the log pattern happens to print.
 */
class BackgroundTaskTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger(BackgroundTask.class);

  /**
   * A ListAppender that freezes each event as it arrives. Logback reads an event's MDC lazily, from
   * whatever thread first asks — without this, a test reading the event after the worker thread
   * finished would see the test thread's MDC and check the wrong thing.
   */
  private final ListAppender<ILoggingEvent> captured = new ListAppender<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };

  @BeforeEach
  void attachAppender() {
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void detachAppenderAndClearMdc() {
    logger.detachAppender(captured);
    MDC.clear();
  }

  @Test
  void failingTask_isLoggedOnceAtError_withStackTraceAndTheCallersIds() throws InterruptedException {
    // ---- Arrange: the caller has a fileId in MDC when it hands the task off ----
    MDC.put("fileId", "file-123");
    Runnable wrapped = BackgroundTask.of("media job", () -> {
      throw new IllegalStateException("ffprobe exited with 1");
    });
    MDC.clear(); // the caller moves on; the task must still carry the id it was given

    // Anything that escapes the task lands here. Before BackgroundTask, with MdcUtils.withMDC,
    // the exception did.
    AtomicReference<Throwable> escaped = new AtomicReference<>();

    // ---- Act: run it the way production does, on a thread nobody waits on for a result ----
    Thread thread = Thread.ofPlatform().uncaughtExceptionHandler((t, e) -> escaped.set(e)).start(wrapped);
    thread.join();

    // ---- Assert ----
    List<ILoggingEvent> errors = errorEvents();
    assertThat(errors).hasSize(1);

    ILoggingEvent error = errors.get(0);
    assertThat(error.getFormattedMessage()).contains("media job");
    assertThat(error.getThrowableProxy()).isNotNull();
    assertThat(error.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
    assertThat(error.getThrowableProxy().getMessage()).isEqualTo("ffprobe exited with 1");
    assertThat(error.getMDCPropertyMap()).containsEntry("fileId", "file-123");

    assertThat(escaped.get()).as("nothing escapes the boundary").isNull();
  }

  @Test
  void succeedingTask_logsNothing_andStillSeesTheCallersIds() throws InterruptedException {
    MDC.put("fileId", "file-123");
    AtomicReference<String> seenByTask = new AtomicReference<>();
    Runnable wrapped = BackgroundTask.of("upload worker", () -> seenByTask.set(MDC.get("fileId")));
    MDC.clear();

    Thread thread = Thread.ofPlatform().start(wrapped);
    thread.join();

    assertThat(errorEvents()).isEmpty();
    assertThat(seenByTask.get()).isEqualTo("file-123");
  }

  @Test
  void pooledThread_hasNoIdsLeftOver_afterAFailingOrSucceedingTask() throws Exception {
    // One pool thread runs everything, so a leak from one task would show up in the next.
    ExecutorService pool = Executors.newSingleThreadExecutor();

    try {
      MDC.put("fileId", "file-123");
      Runnable failing = BackgroundTask.of("failing", () -> {
        throw new IllegalStateException("boom");
      });
      Runnable succeeding = BackgroundTask.of("succeeding", () -> {
      });
      MDC.clear();

      pool.submit(failing).get();
      assertThat(mdcOn(pool)).as("after a failing task").isNullOrEmpty();

      pool.submit(succeeding).get();
      assertThat(mdcOn(pool)).as("after a succeeding task").isNullOrEmpty();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void taskRunOnTheCallersOwnThread_leavesTheCallersIdsInPlace() {
    // A caller-runs rejection policy runs the task on the submitting thread. The caller's MDC must
    // survive that, not be wiped by the task's cleanup.
    MDC.put("fileId", "wrapped-with-this");
    Runnable wrapped = BackgroundTask.of("caller-runs", () -> {
    });

    MDC.put("fileId", "caller-now-has-this");
    wrapped.run();

    assertThat(MDC.get("fileId")).isEqualTo("caller-now-has-this");
  }

  @Test
  void error_isLoggedWithTheIds_andThenRethrown() {
    // An Error (out of memory, stack overflow) is logged for the ids, then allowed to carry on:
    // the JVM is in trouble, and the boundary must not hide that.
    MDC.put("fileId", "file-123");
    Error outOfMemory = new OutOfMemoryError("simulated");
    Runnable wrapped = BackgroundTask.of("media job", () -> {
      throw outOfMemory;
    });

    assertThatThrownBy(wrapped::run).isSameAs(outOfMemory);

    List<ILoggingEvent> errors = errorEvents();
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).getMDCPropertyMap()).containsEntry("fileId", "file-123");
  }

  @Test
  void checkedException_isLoggedOnce_likeAnyOther() {
    // Code below the boundary lets IOException and friends propagate instead of wrapping them.
    // They must arrive here and be reported the same way.
    Runnable wrapped = BackgroundTask.of("upload worker", () -> {
      throw new IOException("connection reset");
    });

    wrapped.run();

    List<ILoggingEvent> errors = errorEvents();
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).getThrowableProxy().getClassName()).isEqualTo(IOException.class.getName());
  }

  @Test
  void interruptedTask_isLogged_andTheInterruptFlagIsPutBack() throws InterruptedException {
    // Catching InterruptedException clears the thread's interrupt flag. The boundary puts it back,
    // so whatever runs next on the thread can still see it was asked to stop.
    AtomicReference<Boolean> flagAfterwards = new AtomicReference<>();
    Runnable wrapped = BackgroundTask.of("normalisation", () -> {
      throw new InterruptedException("shutting down");
    });

    Thread thread = Thread.ofPlatform().start(() -> {
      wrapped.run();
      flagAfterwards.set(Thread.currentThread().isInterrupted());
    });
    thread.join();

    assertThat(errorEvents()).hasSize(1);
    assertThat(flagAfterwards.get()).isTrue();
  }

  private List<ILoggingEvent> errorEvents() {
    return captured.list.stream()
        .filter(event -> event.getLevel() == Level.ERROR)
        .toList();
  }

  /** Reads the MDC as the pool's thread sees it, by running a probe on that same thread. */
  private static Map<String, String> mdcOn(ExecutorService pool) throws Exception {
    return pool.submit(MDC::getCopyOfContextMap).get();
  }
}
