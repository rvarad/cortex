package com.cortex.cortex_common.dto;

import java.util.List;
import java.util.UUID;

/** A whole conversation, in order, for reload. */
public record ConversationDTO(UUID id, String title, List<ConversationMessageDTO> messages) {
}
