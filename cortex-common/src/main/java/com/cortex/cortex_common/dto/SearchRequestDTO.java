package com.cortex.cortex_common.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequestDTO {
  @NotBlank(message = "Search query cannot be empty")
  private String query;
  private UUID fileId;
  private String objectName;
  private String languageCode;

  @Builder.Default
  private int maxResults = 15;
}
