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
public class SearchResultDTO {
  private UUID id;
  private UUID fileId;
  private String fileDisplayName;
  private int chunkIndex;
  private double startTime;
  private double endTime;
  private String transcript;
  private String visualSummary;
  private String languageCode;

  // The RRF combined score
  private double score;
}
