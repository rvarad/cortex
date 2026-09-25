package com.cortex.cortex_rag_orchestration.service.conversation;

import java.util.Map;

/**
 * One tool call as stored on an answer row: which round of the loop it ran in, which tool, and
 * the arguments the model gave it. Never the result.
 *
 * <p>Not private: Jackson has to reach the canonical constructor.
 */
public record RecordedToolCall(int round, String name, Map<String, Object> args) {
}
