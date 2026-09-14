package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.SearchRequestDTO;
import com.cortex.cortex_common.dto.SearchResultDTO;
import com.cortex.cortex_common.dto.SourceRefDTO;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * What the agent is allowed to do. Plain methods over {@link SearchService} — this class knows
 * nothing about loops, models or conversations, so it can be reasoned about and tested alone.
 *
 * <p>Descriptions here are a contract: they describe what the model actually <em>receives</em>,
 * which is {@link SourceFormatter}'s output, not what {@code SearchResultDTO} happens to contain.
 *
 * <p>Each search also records what it found in the {@code RETRIEVED} map, so the loop can turn the
 * ids the model cites back into files and timestamps. Metadata only — never transcripts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryTools {

  /** ToolContext key for the user id. Shared so the two sides can't drift. */
  public static final String USER_ID = "userId";

  /** ToolContext key for the chunks these tools returned, keyed by chunk id. */
  public static final String RETRIEVED = "retrieved";

  private static final String NOTHING_FOUND = "No matching content found in the user's library.";

  private final SearchService searchService;

  private final SourceFormatter sourceFormatter;

  @Tool(name = "search_library", description = """
      Searches the user's media library (audio and video transcripts plus visual summaries). \
      Returns a list of matching chunks. Each chunk has a source id, the file name, start and end \
      timestamps, the transcript text, the visual summary and the chunk index. Cite a claim by \
      putting the source id of the chunk it came from in that segment's cites.""")
  public String searchLibrary(
      @ToolParam(description = """
          The search terms. Use the key words from the user's question, not the whole sentence.""") String query,
      ToolContext toolContext) {

    List<SearchResultDTO> results = searchService.search(
        SearchRequestDTO.builder().query(query).build(), userId(toolContext));

    log.info("[agent] search_library('{}') -> {} chunk(s)", query, results.size());

    return render(results, toolContext);
  }

  @Tool(name = "read_file", description = """
      Reads one whole file from the user's library, when you already know its id. Returns the \
      file's chunks in order, in the same format as search_library. If the file is too large to \
      read whole, falls back to searching within that one file.""")
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

    return render(results, toolContext);
  }

  /** Remember what was found, then hand the model the same chunks as text. */
  private String render(List<SearchResultDTO> results, ToolContext toolContext) {
    if (results.isEmpty()) {
      return NOTHING_FOUND;
    }

    remember(results, retrieved(toolContext));

    return sourceFormatter.formatWithIds(results);
  }

  /**
   * Metadata only. The transcript and visual summary are the bulk of a chunk (~3 KB against ~150
   * bytes for everything else) and the loop never needs them again — they have already gone to the
   * model as text. {@code sourceNo} is left unset; numbers are assigned after the answer arrives.
   */
  private void remember(List<SearchResultDTO> results, Map<UUID, SourceRefDTO> retrieved) {
    for (SearchResultDTO result : results) {
      retrieved.putIfAbsent(result.getId(), SourceRefDTO.builder()
          .fileId(result.getFileId())
          .fileDisplayName(result.getFileDisplayName())
          .startTime(result.getStartTime())
          .endTime(result.getEndTime())
          .chunkIndex(result.getChunkIndex())
          .build());
    }
  }

  /**
   * The user id arrives out-of-band, so the model can neither see it nor set it. Absent means the
   * caller is wired wrong — fail loudly rather than search an unknown user's library.
   */
  private String userId(ToolContext toolContext) {
    Object userId = toolContext.getContext().get(USER_ID);

    if (userId == null) {
      throw new IllegalStateException(
          "userId missing from ToolContext - the caller must supply it on every model call");
    }

    return userId.toString();
  }

  /**
   * Same rule as {@link #userId}: absent means miswired, and answers would be uncitable.
   *
   * <p>The cast is unchecked because {@code ToolContext} is a {@code Map<String, Object>} and
   * generics are erased at runtime — the JVM can confirm "a Map" but not "a Map of UUID to
   * SourceRefDTO". Suppressed rather than handled because both ends of the contract are ours:
   * {@code AgentService.options} puts this exact map in, under {@link #RETRIEVED}.
   */
  @SuppressWarnings("unchecked")
  private Map<UUID, SourceRefDTO> retrieved(ToolContext toolContext) {
    Object retrieved = toolContext.getContext().get(RETRIEVED);

    if (retrieved == null) {
      throw new IllegalStateException(
          "retrieved map missing from ToolContext - the caller must supply it on every model call");
    }

    return (Map<UUID, SourceRefDTO>) retrieved;
  }
}
