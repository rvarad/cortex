package com.cortex.cortex_common.utils;

import java.util.Map;

import org.slf4j.MDC;

import lombok.extern.slf4j.Slf4j;

/**
 * The boundary for fire-and-forget work (ADR-0014). Every task handed to a thread that nobody
 * waits on starts inside {@link #of}. A task whose result is waited for ({@code get()},
 * {@code join()}) must not be wrapped: its failure already travels back to the waiting code, and
 * wrapping it would log the same failure twice.
 *
 * <p>Two jobs, together so no thread can have one without the other: carry the caller's MDC
 * ({@code fileId} and friends) onto the new thread, and make sure a failure ends in one ERROR line
 * with those ids instead of a stack trace on stderr that nothing reads.
 */
@Slf4j
public final class BackgroundTask {

  private BackgroundTask() {
  }

  /**
   * @param name what the task is, for the log line — "media job", "upload worker". MDC says which
   *     file; this says which of that file's threads failed.
   * @param task the work. Anything it throws is logged here and goes no further.
   */
  public static Runnable of(String name, Runnable task) {
    // Captured now, on the caller's thread: by the time the task runs, the caller's MDC may
    // have moved on.
    Map<String, String> callerContext = MDC.getCopyOfContextMap();

    return () -> {
      Map<String, String> previousContext = MDC.getCopyOfContextMap();
      setContext(callerContext);

      try {
        task.run();
      } catch (Exception e) {
        log.error("[{}] failed", name, e);
      } catch (Error e) {
        // Logged for the ids, then rethrown. An Error (out of memory, stack overflow) means the
        // JVM itself is in trouble; swallowing it would hide that from the thread's own handler.
        // The cost: the JVM prints it a second time, to stderr, without the ids.
        log.error("[{}] failed with an Error", name, e);
        throw e;
      } finally {
        // Restored, not cleared: if the task ever runs on the caller's own thread (a
        // caller-runs rejection policy), clearing would wipe the caller's ids.
        setContext(previousContext);
      }
    };
  }

  private static void setContext(Map<String, String> context) {
    if (context == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(context);
    }
  }
}
