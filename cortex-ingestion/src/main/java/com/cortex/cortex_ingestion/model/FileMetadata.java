package com.cortex.cortex_ingestion.model;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.lang.NonNull;

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
@Table(name = "file_metadata")
public class FileMetadata {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @NonNull
  @NotBlank(message = "File name cannot be empty")
  @Column(nullable = false)
  private String fileDisplayName;

  @NonNull
  @NotBlank(message = "Bucket name cannot be empty")
  @Column(nullable = false)
  private String bucketName;

  @NonNull
  @NotBlank(message = "Object name cannot be empty")
  @Column(nullable = false)
  private String objectName;

  @NonNull
  @NotNull(message = "File size cannot be empty")
  @Column(nullable = false)
  private Long fileSize;

  @NonNull
  @NotNull(message = "File status cannot be empty")
  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private FileStatus fileStatus;

  @NonNull
  @NotBlank(message = "Content type cannot be empty")
  @Column(nullable = false)
  private String contentType;

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    this.createdAt = LocalDateTime.now();
  }
}