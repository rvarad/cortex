package com.cortex.cortex_rag_orchestration.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One turn of a conversation, either side.
 *
 * <p>{@code content} is JSON whose shape depends on the role: {@code {"text"}} for the user,
 * {@code {"segments","sources"}} for the assistant. The answer is kept structured — segments with
 * their cites, plus the sources those cites number — because a citation flattened into prose is
 * gone for good, and a reloaded chat has to click through exactly as the live one did. The service
 * layer owns the (de)serialisation; this class stores the string, as {@code PipelineEvent} does for
 * its metadata.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "conversation_message")
public class ConversationMessage {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** A plain id, not a {@code @ManyToOne} — the same way {@code MediaChunk.fileId} is done. */
  @Column(nullable = false, updatable = false)
  private UUID conversationId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private MessageRole role;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String content;

  /**
   * What the user attached to this message. Stored so the reloaded chat can draw the chips, and for
   * nothing else — attachments are never replayed to the model (F3). Empty on assistant rows.
   */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "uuid[]")
  private List<UUID> attachedFileIds;

  /**
   * The tools the model called to produce this answer, in order: a JSON array of
   * {@code {round, name, args}}. What it did, where {@code content} is what it said — kept apart
   * for the same reason as {@code attachedFileIds}. Results are not stored. {@code "[]"} on user
   * rows and on answers that needed no tools.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String toolCalls;

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    this.createdAt = LocalDateTime.now();
    if (this.attachedFileIds == null) {
      this.attachedFileIds = List.of();
    }
    if (this.toolCalls == null) {
      this.toolCalls = "[]";
    }
  }
}
