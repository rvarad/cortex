package com.cortex.cortex_media_processing_service.service.processing;

import com.cortex.cortex_common.dto.MediaFileManifestDTO;

import lombok.Data;

@Data
public class ChunkPair {
  private String videoPath;
  private String audioPath;
  private double start_s;
  private double end_s;

  public boolean isComplete(MediaFileManifestDTO manifestDTO) {
    boolean videoSatisfied = !manifestDTO.isHasVideo() || videoPath != null;
    boolean audioSatisfied = !manifestDTO.isHasAudio() || audioPath != null;

    return videoSatisfied && audioSatisfied;
  }
}
