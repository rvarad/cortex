package com.cortex.cortex_common.model;

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
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "media_chunk")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MediaChunk {

  public enum Status {
    UPLOADED,
    IN_PROGRESS,
    COMPLETED,
    FAILED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID fileId;

  private int chunkIndex;

  private double startTime;

  private double endTime;

  @Enumerated(EnumType.STRING)
  private Status status;

  @Column
  private String languageCode;

  @Column(columnDefinition = "TEXT")
  private String transcript;

  @Column(columnDefinition = "TEXT")
  private String visualSummary;

  @NotNull(message = "User ID cannot be empty")
  @Column(nullable = false, updatable = false)
  private String userId;

  @JdbcTypeCode(SqlTypes.VECTOR)
  @Column(columnDefinition = "vector(768)")
  private float[] embedding;
}