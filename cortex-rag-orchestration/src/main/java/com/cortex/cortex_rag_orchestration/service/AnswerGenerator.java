package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;
import java.util.List;
import java.util.function.Consumer;

public interface AnswerGenerator {
  List<AnswerSegmentDTO> generateAnswer(String prompt);

  public void generateAnswerStream(String prompt, Consumer<AnswerSegmentDTO> onSegmentComplete);
}
