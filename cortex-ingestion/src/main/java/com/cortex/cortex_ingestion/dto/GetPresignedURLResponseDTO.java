package com.cortex.cortex_ingestion.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetPresignedURLResponseDTO {
  private String uploadUrl;
  private UUID fileId;
  private LocalDateTime expiresIn;
}