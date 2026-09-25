package com.cortex.cortex_rag_orchestration.controller;

import com.cortex.cortex_rag_orchestration.service.AgentEvent;
import com.cortex.cortex_rag_orchestration.service.SegmentEvent;
import com.cortex.cortex_rag_orchestration.service.SourceEvent;
import com.cortex.cortex_rag_orchestration.service.StepEvent;
import com.cortex.cortex_rag_orchestration.service.ToolCallEvent;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * How agent events go on the wire. One place, so the stateless and the conversation endpoints
 * send byte-identical streams and the frontend parser has one contract to read.
 */
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
      throw new RuntimeException("Error sending SSE event: " + eventName, e);
    }
  }
}
