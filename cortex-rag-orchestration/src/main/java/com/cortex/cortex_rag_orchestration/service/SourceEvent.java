package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.SourceRefDTO;

/**
 * A citation's file and timestamps, sent just before the first segment that cites it, and only
 * once per source.
 */
public record SourceEvent(SourceRefDTO source) implements AgentEvent {
}
