package com.cortex.cortex_rag_orchestration.controller;

import com.cortex.cortex_common.utils.BackgroundTask;
import com.cortex.cortex_rag_orchestration.service.AgentEvent;
import com.cortex.cortex_rag_orchestration.service.SegmentEvent;
import com.cortex.cortex_rag_orchestration.service.SourceEvent;
import com.cortex.cortex_rag_orchestration.service.StepEvent;
import com.cortex.cortex_rag_orchestration.service.ToolCallEvent;
import java.io.IOException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * How agent events go on the wire. One place, so the stateless and the conversation endpoints
 * send byte-identical streams and the frontend parser has one contract to read.
 */
@Slf4j
final class SseEvents {

  private SseEvents() {
  }

  /** Sealed event type, so the compiler checks every kind has a place on the wire. */
  static void send(SseEmitter emitter, AgentEvent event) {
    switch (event) {
      case StepEvent step -> send(emitter, "step", Map.of("label", step.label()));
      case SourceEvent source -> send(emitter, "source", source.source());
      case SegmentEvent segment -> send(emitter, "segment", segment.segment());
      // Recorded by the conversation layer, never shown: the user sees steps, not calls.
      case ToolCallEvent ignored -> {
      }
    }
  }

  /** An explicit end, so the client can tell "finished" from "connection dropped". */
  static void done(SseEmitter emitter) {
    send(emitter, "done", Map.of());
  }

  static void send(SseEmitter emitter, String eventName, Object data) {
    try {
      emitter.send(SseEmitter.event().name(eventName).data(data));
    } catch (IOException e) {
      // Thrown, not swallowed: this is how a client going away stops the agent.
      throw new ClientDisconnectedException(eventName, e);
    }
  }

  /**
   * One answer stream, as a task for its own thread. Nothing waits for that thread — the controller
   * has already returned the emitter — so the top of it is the answer's boundary (ADR-0014), and
   * this is the one place a failed answer is handled.
   *
   * <p>On success the stream is completed. A client that left is logged at INFO: it is expected,
   * and the failed send is what stopped the agent. Anything else closes the stream with the error
   * and is thrown on to BackgroundTask, which logs it once.
   *
   * <p>Callers put their ids (conversationId) in MDC before calling this, so the boundary's log
   * line carries them.
   *
   * @param name what the stream is, for the log line
   * @param answer the work that writes to the emitter, including any closing {@code done} event
   */
  static Runnable answering(SseEmitter emitter, String name, BackgroundTask.Work answer) {
    return BackgroundTask.of(name, () -> {
      try {
        answer.run();
        emitter.complete();
      } catch (ClientDisconnectedException e) {
        log.info("[{}] client disconnected; answer stopped", name);
      } catch (Exception e) {
        // Closes the stream so the client stops waiting; the boundary above does the logging.
        emitter.completeWithError(e);
        throw e;
      }
    });
  }
}
