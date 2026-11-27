package com.cortex.cortex_common.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** One row of the previous-chats sidebar. */
public record ConversationSummaryDTO(
    UUID id,
    String title,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {
}
