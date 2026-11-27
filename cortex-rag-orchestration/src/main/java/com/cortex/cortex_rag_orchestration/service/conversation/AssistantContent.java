package com.cortex.cortex_rag_orchestration.service.conversation;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;
import com.cortex.cortex_common.dto.SourceRefDTO;
import java.util.List;

/**
 * What an {@code ASSISTANT} row's {@code content} column holds: the answer exactly as it was
 * streamed. The sources keep the {@code sourceNo} they were given live, so the cites in the
 * segments resolve on reload without any renumbering.
 *
 * <p>Not private: Jackson has to reach the canonical constructor.
 */
record AssistantContent(List<AnswerSegmentDTO> segments, List<SourceRefDTO> sources) {

  /** The answer as the model will see it when replayed: the words, without the footnotes. */
  String text() {
    StringBuilder text = new StringBuilder();

    for (AnswerSegmentDTO segment : segments) {
      if (segment.getText() == null || segment.getText().isBlank()) {
        continue;
      }
      if (!text.isEmpty()) {
        text.append(' ');
      }
      text.append(segment.getText().strip());
    }

    return text.toString();
  }
}
