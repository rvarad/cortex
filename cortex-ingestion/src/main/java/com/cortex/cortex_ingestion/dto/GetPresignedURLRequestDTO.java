package com.cortex.cortex_ingestion.dto;

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
public class GetPresignedURLRequestDTO {
  @NotBlank
  private String filename;

  @NotBlank
  private String contentType;

  @NotNull
  private Long fileSize;
}