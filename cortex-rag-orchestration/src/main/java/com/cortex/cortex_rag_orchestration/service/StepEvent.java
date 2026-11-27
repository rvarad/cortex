package com.cortex.cortex_rag_orchestration.service;

/**
 * Something the agent is doing, in words a user can read — "Searching library…".
 *
 * <p>This is what makes the wait legible. A question takes several seconds and several model calls;
 * without these the user watches a spinner and assumes it is broken.
 */
public record StepEvent(String label) implements AgentEvent {
}
