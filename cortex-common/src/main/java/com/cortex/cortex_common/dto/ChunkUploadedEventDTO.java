package com.cortex.cortex_common.dto;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkUploadedEventDTO {

  private UUID chunkId;
  private String objectName;
  private int chunkIndex;
  private double start_s;
  private double end_s;
  private String videoPath;
  private String audioPath;
}
