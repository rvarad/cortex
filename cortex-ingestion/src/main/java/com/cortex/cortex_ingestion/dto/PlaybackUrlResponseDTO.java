package com.cortex.cortex_ingestion.dto;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackUrlResponseDTO {
  private String playbackUrl;
  private Instant expiresAt;
  private Boolean hasVideo;
}
