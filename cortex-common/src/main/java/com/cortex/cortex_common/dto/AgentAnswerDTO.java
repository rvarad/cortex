package com.cortex.cortex_common.dto;

/**
 * The result of one agent run.
 *
 * @param text the model's final answer
 * @param hitIterationCap true when the loop was stopped by its safety cap rather than by the model
 *     deciding it was done. The text is then whatever it managed, not a complete answer — kept as
 *     its own field so callers can tell "answered" from "gave up".
 */
public record AgentAnswerDTO(String text, boolean hitIterationCap) {
}
