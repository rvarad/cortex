package com.cortex.cortex_common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class MediaFileManifestDTO {

  private boolean hasVideo;
  private boolean hasAudio;
  private double duration_s;

  public boolean isCorrupted() {
    return !hasVideo && !hasAudio;
  }
}