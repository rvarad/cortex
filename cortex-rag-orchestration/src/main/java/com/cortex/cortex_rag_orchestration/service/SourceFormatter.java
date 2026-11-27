package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.SearchResultDTO;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders retrieved chunks into the block of text the model reads.
 *
 * <p>
 * Two variants, differing only in how each chunk is identified:
 *
 * <ul>
 * <li>{@link #format} — numbered, for the original chat path, whose prompt asks
 * the model to cite
 * "Source no." integers.
 * <li>{@link #formatWithIds} — carries each chunk's real id, for the agent.
 * Numbering restarts at
 * 1 on every search, so with several searches in one question the same number
 * would mean two
 * different chunks. Ids cannot collide.
 * </ul>
 *
 * <p>
 * The field block itself lives in one place on purpose. If it were duplicated
 * and a field were
 * added to one variant only, the two chat paths would start showing the model
 * different things and
 * nothing would fail.
 */
@Component
public class SourceFormatter {

  /**
   * Numbered sources. The original chat path depends on this output exactly as it
   * is.
   */
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

  /**
   * Sources identified by chunk id, so citations survive several searches in one
   * question.
   */
  public String formatWithIds(List<SearchResultDTO> searchResults) {
    StringBuilder context = new StringBuilder();

    for (SearchResultDTO result : searchResults) {
      context.append("{\n").append("Source id: ").append(result.getId()).append("\n");
      context.append("fileId: ").append(result.getFileId()).append("\n");
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