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
public class SourceRefDTO {
  private int sourceNo;
  private double startTime;
  private double endTime;
  private UUID fileId;
  private String fileDisplayName;
  private int chunkIndex;

  /**
   * True when this chunk came from a file the user attached to the message, false when it was found
   * by searching the library. A fact about where the chunk came from, not something the model
   * claims — so the UI can mark each citation's origin rather than making one blanket statement.
   */
  private boolean attached;
}
