package com.cortex.cortex_media_processing_service.model;

import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private String objectName;

  private int chunkIndex;

  private double startTime;

  private double endTime;

  @Column(columnDefinition = "TEXT")
  private String transcript;

  @Column(columnDefinition = "TEXT")
  private String visualSummary;

  @JdbcTypeCode(SqlTypes.VECTOR)
  @Column(columnDefinition = "vector(768)")
  private float[] embedding;
}

// Here are 12 descriptions of a video taken over 60 seconds. Summarize the
// visual action into one concise paragraph, noting any key changes or text
// visible on screen. Ignore repetitive frames.