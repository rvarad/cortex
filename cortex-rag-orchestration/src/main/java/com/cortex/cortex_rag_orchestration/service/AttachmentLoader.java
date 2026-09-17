package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.SearchRequestDTO;
import com.cortex.cortex_common.dto.SearchResultDTO;
import com.cortex.cortex_common.dto.SourceRefDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads the files attached to a message into text the model can read, before any model call.
 *
 * <p>The user already decided those files are relevant, so making the model ask for them would cost
 * a round trip — four to six seconds — for a decision that has already been made. Attachments
 * belong to one message, not to the conversation, so this does nothing on most turns.
 *
 * <p>Files are taken in order until the budget runs out. Whatever does not fit is reported rather
 * than dropped, with its file id, so the model can reach it with {@code read_file}.
 *
 * <p>Knows nothing about models, loops or tools: ids in, text and notes out.
 */
@Slf4j
@Component
class AttachmentLoader {

  private final SearchService searchService;

  private final SourceFormatter sourceFormatter;

  /**
   * The aggregate cap across all attachments. Deliberately the same number {@code getFileContext}
   * uses for a single file, so several attachments cost no more than one big file already does.
   */
  private final int tokenBudget;

  AttachmentLoader(SearchService searchService, SourceFormatter sourceFormatter,
      @Value("${chat.context.token-budget}") int tokenBudget) {
    this.searchService = searchService;
    this.sourceFormatter = sourceFormatter;
    this.tokenBudget = tokenBudget;
  }

  /**
   * @param fileIds attached to this message; null or empty on most turns
   * @param question used only when a file is too large to read whole — {@code getFileContext} then
   *     searches within that one file, and a search needs something to search for
   * @param retrieved written into, so the ids the model cites can be resolved. Without this,
   *     attached content would be the one thing in the system that cannot be cited.
   */
  LoadedAttachments load(List<UUID> fileIds, String question, String userId,
      Map<UUID, SourceRefDTO> retrieved) {

    if (fileIds == null || fileIds.isEmpty()) {
      return LoadedAttachments.NONE;
    }

    StringBuilder text = new StringBuilder();
    List<String> notes = new ArrayList<>();

    int remaining = tokenBudget;
    int loaded = 0;

    for (UUID fileId : fileIds) {

      // Returns the whole file when it fits its own budget, and degrades to searching within that
      // one file when it does not.
      List<SearchResultDTO> chunks = searchService.getFileContext(
          SearchRequestDTO.builder().fileId(fileId).query(question).build(), userId);

      if (chunks.isEmpty()) {
        log.warn("[agent] attached file {} returned nothing — wrong user, or deleted", fileId);
        notes.add("File " + fileId + " is not available.");
        continue;
      }

      String fileName = chunks.get(0).getFileDisplayName();
      int cost = estimateTokens(chunks);

      if (cost > remaining) {
        log.info("[agent] attachment '{}' skipped: ~{} tokens, {} left", fileName, cost, remaining);
        notes.add(fileName + " (file id: " + fileId
            + ") was too large to include in full. Use read_file with that id if you need it.");
        continue;
      }

      // true: the user attached this. That flag is the whole provenance feature.
      SourceRefs.remember(retrieved, chunks, true);

      text.append(sourceFormatter.formatWithIds(chunks));

      remaining -= cost;
      loaded++;
    }

    log.info("[agent] attachments: {} of {} loaded, ~{} tokens used", loaded, fileIds.size(),
        tokenBudget - remaining);

    return new LoadedAttachments(text.toString(), notes);
  }

  /** Same estimate {@code SearchService} uses for its own budget, so the two agree. */
  private int estimateTokens(List<SearchResultDTO> chunks) {
    long chars = chunks.stream()
        .mapToLong(chunk -> length(chunk.getTranscript()) + length(chunk.getVisualSummary()))
        .sum();

    return (int) (chars / 4);
  }

  private int length(String text) {
    return text == null ? 0 : text.length();
  }
}
