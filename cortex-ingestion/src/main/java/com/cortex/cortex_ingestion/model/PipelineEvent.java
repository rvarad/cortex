package com.cortex.cortex_ingestion.model;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.lang.NonNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cortex.cortex_common.model.PipelineEventEnum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "pipeline_events")
public class PipelineEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NonNull
  @NotNull(message = "File ID must not be null")
  @Column(nullable = false)
  private UUID fileId;

  @Column(nullable = true)
  private UUID chunkId;

  @Column(nullable = true)
  private Integer chunkIndex;

  @NonNull
  @NotNull(message = "Event type must not be null")
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PipelineEventEnum eventType;

  @NonNull
  @NotBlank(message = "Message must not be null or blank")
  @Column(nullable = false)
  private String message;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String metadata;

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    this.createdAt = LocalDateTime.now();
  }
}
