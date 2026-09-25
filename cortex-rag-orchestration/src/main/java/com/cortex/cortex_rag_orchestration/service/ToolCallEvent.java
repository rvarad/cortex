package com.cortex.cortex_rag_orchestration.service;

/**
 * A tool call that is about to run: the loop round it belongs to, the tool, and the model's
 * arguments as the raw JSON Spring AI hands over.
 *
 * <p>Separate from {@link StepEvent} on purpose. A step is what the <em>user</em> is shown, and
 * some calls show nothing (a recall is just "thinking"). This is what gets <em>recorded</em>, and
 * every call is. It never goes to the browser.
 */
public record ToolCallEvent(int round, String name, String arguments) implements AgentEvent {
}
