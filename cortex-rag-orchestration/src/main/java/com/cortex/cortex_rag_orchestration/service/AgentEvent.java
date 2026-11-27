package com.cortex.cortex_rag_orchestration.service;

/**
 * What the agent reports while it works.
 *
 * <p>Sealed so the controller's switch is checked by the compiler: add a fourth kind of event and
 * every place that maps events to the wire stops compiling until it is handled.
 *
 * <p>These travel through a callback rather than being written to the response directly, so Slice
 * 2's conversation layer can sit between the agent and the controller and persist segments as they
 * stream.
 */
public sealed interface AgentEvent permits StepEvent, SourceEvent, SegmentEvent, ToolCallEvent {
}
