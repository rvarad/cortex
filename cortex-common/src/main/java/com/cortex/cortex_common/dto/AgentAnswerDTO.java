package com.cortex.cortex_common.dto;

import java.util.List;

/**
 * The result of one agent run.
 *
 * @param answer the answer in segments, each carrying the source numbers backing it
 * @param sources every source the answer cites, numbered from 1 in order of first appearance
 * @param hitIterationCap true when the loop was stopped by its safety cap rather than by the model
 *     deciding it was done. The answer is then whatever it managed, not a complete one — kept as its
 *     own field so callers can tell "answered" from "gave up".
 */
public record AgentAnswerDTO(
    List<AnswerSegmentDTO> answer,
    List<SourceRefDTO> sources,
    boolean hitIterationCap) {
}
