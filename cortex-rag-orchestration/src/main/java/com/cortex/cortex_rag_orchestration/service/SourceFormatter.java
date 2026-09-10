package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.SearchResultDTO;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders retrieved chunks into the numbered "Source no." block the model reads.
 *
 * <p>Shared deliberately: the existing chat path and the agent's tools must present sources in the
 * identical shape, or the model is being asked two different questions and answers can't be
 * compared between them.
 */
@Component
public class SourceFormatter {

  public String format(List<SearchResultDTO> searchResults) {
    StringBuilder context = new StringBuilder();

    for (int i = 0; i < searchResults.size(); i++) {
      SearchResultDTO result = searchResults.get(i);

      context.append("{\n").append("Source no.: ").append(i + 1).append("\n");
      context.append("fileName: ").append(result.getFileDisplayName()).append("\n");
      context.append("startTime: ").append(result.getStartTime()).append("\n");
      context.append("endTime: ").append(result.getEndTime()).append("\n");
      context.append("transcript: ").append(result.getTranscript()).append("\n");
      context.append("visualSummary: ").append(result.getVisualSummary()).append("\n");
      context.append("chunkIndex: ").append(result.getChunkIndex()).append("}\n\n");
    }

    return context.toString();
  }
}
