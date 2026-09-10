package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.SearchRequestDTO;
import com.cortex.cortex_common.dto.SearchResultDTO;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * What the agent is allowed to do. Plain methods over {@link SearchService} —
 * this class knows
 * nothing about loops, models or conversations, so it can be reasoned about and
 * tested alone.
 *
 * <p>
 * Descriptions here are a contract: they describe what the model actually
 * <em>receives</em>,
 * which is {@link SourceFormatter}'s output, not what {@code SearchResultDTO}
 * happens to contain.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryTools {

  /**
   * Key the caller must put in the ToolContext. Shared so the two sides can't
   * drift.
   */
  public static final String USER_ID = "userId";

  private static final String NOTHING_FOUND = "No matching content found in the user's library.";

  private final SearchService searchService;

  private final SourceFormatter sourceFormatter;

  @Tool(name = "search_library", description = """
      Searches the user's media library (audio and video transcripts plus visual summaries). \
      Returns a numbered list of matching chunks. Each chunk has a source number, the file name, \
      start and end timestamps, the transcript text, the visual summary and the chunk index.""")
  public String searchLibrary(
      @ToolParam(description = """
          The search terms. Use the key words from the user's question, not the whole sentence.""") String query,
      ToolContext toolContext) {

    List<SearchResultDTO> results = searchService.search(
        SearchRequestDTO.builder().query(query).build(), userId(toolContext));

    log.info("[agent] search_library('{}') -> {} chunk(s)", query, results.size());

    return results.isEmpty() ? NOTHING_FOUND : sourceFormatter.format(results);
  }

  @Tool(name = "read_file", description = """
      Reads one whole file from the user's library, when you already know its id. Returns the \
      file's chunks in order, in the same numbered format as search_library. If the file is too \
      large to read whole, falls back to searching within that one file.""")
  public String readFile(
      @ToolParam(description = """
          The id of the file to read, as a UUID.""") String fileId,
      @ToolParam(description = """
          What you are looking for in this file. Used only if the file is too large to read whole.""") String query,
      ToolContext toolContext) {

    UUID parsedFileId;

    try {
      parsedFileId = UUID.fromString(fileId);
    } catch (IllegalArgumentException e) {
      // Returned, not thrown: a thrown exception ends the loop, a message lets the
      // agent correct.
      log.warn("[agent] read_file called with a malformed file id: {}", fileId);
      return "'" + fileId + "' is not a valid file id. File ids are UUIDs.";
    }

    List<SearchResultDTO> results = searchService.getFileContext(
        SearchRequestDTO.builder().fileId(parsedFileId).query(query).build(), userId(toolContext));

    log.info("[agent] read_file({}) -> {} chunk(s)", parsedFileId, results.size());

    return results.isEmpty() ? NOTHING_FOUND : sourceFormatter.format(results);
  }

  /**
   * The user id arrives out-of-band, so the model can neither see it nor set it.
   * Absent means the
   * caller is wired wrong — fail loudly rather than search an unknown user's
   * library.
   */
  private String userId(ToolContext toolContext) {
    Object userId = toolContext.getContext().get(USER_ID);

    if (userId == null) {
      throw new IllegalStateException(
          "userId missing from ToolContext - the caller must supply it on every model call");
    }

    return userId.toString();
  }
}
