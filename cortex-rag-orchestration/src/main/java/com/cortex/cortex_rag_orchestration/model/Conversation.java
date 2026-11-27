package com.cortex.cortex_rag_orchestration.model;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One chat: an owner and a title. The messages point at it; attachments live on the message that
 * carried them, not here (plan §5.2).
 *
 * <p>Lives in this service rather than {@code cortex-common} because only this service reads or
 * writes it — the same split ingestion uses for {@code PipelineEvent}.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "conversation")
public class Conversation {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, updatable = false)
  private String userId;

  /** The first question, trimmed. Enough to recognise a chat in the sidebar. */
  @Column(nullable = false)
  private String title;

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /** Bumped on every message, so the sidebar orders by recent activity without a join. */
  @Column(nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;
  }
}
