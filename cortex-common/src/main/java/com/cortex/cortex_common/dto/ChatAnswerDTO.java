package com.cortex.cortex_common.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatAnswerDTO {

  private List<AnswerSegmentDTO> answer;
  private List<SearchResultDTO> sources;
}
