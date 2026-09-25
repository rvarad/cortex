package com.cortex.cortex_rag_orchestration.service.conversation;

/**
 * What a {@code USER} row's {@code content} column holds. Not private: Jackson has to reach the
 * canonical constructor.
 */
record UserContent(String text) {
}
