package com.cortex.cortex_ingestion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GetPresignedURLRequestDTO {
  private String filename;
  private String contentType;
  private Long size;
}