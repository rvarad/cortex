package com.cortex.cortex_rag_orchestration.controller;

/**
 * A send to an SSE stream failed because the client is gone: the tab closed, the user pressed
 * stop, the connection dropped. Expected, not an error — throwing it is how a client leaving stops
 * the agent, and the stream's boundary logs it at INFO (ADR-0014).
 */
final class ClientDisconnectedException extends RuntimeException {

  ClientDisconnectedException(String eventName, Throwable cause) {
    super("Client disconnected while sending SSE event: " + eventName, cause);
  }
}
