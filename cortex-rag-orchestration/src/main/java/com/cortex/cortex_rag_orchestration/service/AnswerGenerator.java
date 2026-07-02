package com.cortex.cortex_rag_orchestration.service;

import java.util.List;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;

public interface AnswerGenerator {
  List<AnswerSegmentDTO> generateAnswer(String prompt);
}
