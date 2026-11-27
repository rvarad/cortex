package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;

/** One piece of the answer, with its cites already turned into numbers. */
public record SegmentEvent(AnswerSegmentDTO segment) implements AgentEvent {
}
