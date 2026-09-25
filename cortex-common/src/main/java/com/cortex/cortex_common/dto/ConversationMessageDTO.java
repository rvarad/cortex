package com.cortex.cortex_common.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One message of a reloaded conversation. Which fields are set depends on {@code role}:
 *
 * <ul>
 *   <li>{@code USER} — {@code text} and {@code attachments}; the rest null.
 *   <li>{@code ASSISTANT} — {@code segments} and {@code sources}, exactly as they were streamed,
 *       so the cites in the segments resolve against the sources without renumbering; the rest
 *       null.
 * </ul>
 */
public record ConversationMessageDTO(
    UUID id,
    String role,
    LocalDateTime createdAt,
    String text,
    List<AttachedFileDTO> attachments,
    List<AnswerSegmentDTO> segments,
    List<SourceRefDTO> sources) {
}
