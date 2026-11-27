package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.SearchRequestDTO;
import com.cortex.cortex_common.dto.SearchResultDTO;
import com.cortex.cortex_common.dto.SourceRefDTO;
import com.cortex.cortex_rag_orchestration.service.conversation.ConversationHistory;
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
 * What the agent is allowed to do. Plain methods over {@link SearchService} and
 * {@link
 * ConversationHistory} — this class knows nothing about loops or models, so it
 * can be reasoned
 * about and tested alone.
 *
 * <p>
 * Two tools read the library; the third reads the stored conversation, for
 * turns the replay
 * window left out (plan §5.4).
 *
 * <p>
 * Descriptions here are a contract: they describe what the model actually
 * <em>receives</em>,
 * which is {@link SourceFormatter}'s output, not what {@code SearchResultDTO}
 * happens to contain.
 *
 * <p>
 * Each search also records what it found in the {@code RETRIEVED} map, so the
 * loop can turn the
 * ids the model cites back into files and timestamps. Metadata only — never
 * transcripts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryTools {

  /** ToolContext key for the user id. Shared so the two sides can't drift. */
  public static final String USER_ID = "userId";

  /**
   * ToolContext key for the stored conversation this question belongs to. Absent
   * on the stateless
   * path, where there is nothing to recall.
   */
  public static final String CONVERSATION_ID = "conversationId";

  /** ToolContext key for the chunks these tools returned, keyed by chunk id. */
  public static final String RETRIEVED = "retrieved";

  private static final String NOTHING_FOUND = "No matching content found in the user's library.";

  private static final String NOTHING_NEW_SEARCH = "Every passage this search found is already in "
      + "front of you in this conversation. Searching again with similar words won't find anything "
      + "new. Answer from what you have, or search for something clearly different.";

  private static final String NOTHING_NEW_FILE = "You've already seen everything in this file "
      + "during this question. Reading it again won't show anything new.";

  private final SearchService searchService;

  private final SourceFormatter sourceFormatter;

  private final ConversationHistory conversationHistory;

  @Tool(name = "search_library", description = """
      Use this when the answer isn't in the conversation or the attached files. \
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

    return render(results, toolContext, "search_library('" + query + "')", NOTHING_NEW_SEARCH);
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

    return render(results, toolContext, "read_file(" + parsedFileId + ")", NOTHING_NEW_FILE);
  }

  @Tool(name = "recall_conversation", description = """
      Reads earlier turns of this conversation that are not shown in the prompt. Use it when the \
      user refers to something (a name, "that", "the other one") that you can't find in the \
      turns you can see — check here before searching the library. Page 0 is the block of turns \
      just before the ones you can see; page 1 is the block before that, and so on. Turns come \
      back oldest first, each with the user's question, any files attached to it, the tools you \
      called to answer it, your answer, and the files that answer cited (file name and fileId). \
      Tool results are not included. Those file references CANNOT be \
      cited — a cite must come from a chunk fetched in this request. To cite something from an \
      earlier turn, call read_file with its fileId and cite what that returns.""")
  public String recallConversation(
      @ToolParam(description = """
          Which block of earlier turns to read: 0 for the most recent ones not shown, 1 for the \
          block before that, and so on. Start at 0.""") int page,
      ToolContext toolContext) {

    Object conversationId = toolContext.getContext().get(CONVERSATION_ID);

    if (conversationId == null) {
      // The stateless path: nothing stored, so nothing to recall. Returned, not
      // thrown, so the
      // model can answer from what it has instead of the loop ending.
      //
      log.info("[agent] recall_conversation called with no conversation; nothing to recall");
      return "No conversation history is available.";
    }

    List<ConversationHistory.Turn> recalled = conversationHistory.recall(
        (UUID) conversationId, userId(toolContext), page);

    log.info("[agent] recall_conversation(page={}) -> {} turn(s)", page, recalled.size());

    if (recalled.isEmpty()) {
      return page == 0
          ? "This conversation has no earlier turns beyond what is shown."
          : "No more turns before page " + page + ".";
    }

    return conversationHistory.render(recalled);
  }

  /**
   * Remember what was found, then hand the model the same chunks as text.
   *
   * <p>If every chunk is one the model has already seen in this request — from the attachments,
   * or an earlier search or read — the result is replaced with {@code nothingNew}. Those passages
   * are already in the prompt, and the model kept re-running near-identical searches and re-reading
   * files it had read in full, while a prompt line telling it not to was ignored. Said in code, it
   * holds. Partly new results are returned whole.
   */
  private String render(List<SearchResultDTO> results, ToolContext toolContext, String call,
      String nothingNew) {
    if (results.isEmpty()) {
      return NOTHING_FOUND;
    }

    Map<UUID, SourceRefDTO> retrieved = retrieved(toolContext);

    if (results.stream().allMatch(result -> retrieved.containsKey(result.getId()))) {
      log.info("[agent] {} -> nothing new ({} chunk(s) already seen)", call, results.size());
      return nothingNew;
    }

    // false: these were found by searching, not attached by the user.
    SourceRefs.remember(retrieved, results, false);

    return sourceFormatter.formatWithIds(results);
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

  /**
   * Same rule as {@link #userId}: absent means miswired, and answers would be
   * uncitable.
   *
   * 
   * <p>
   * The cast is unchecked because {@code ToolContext} is a
   * {@code Map<String, Object>} and
   * gen
   * erics are erased at runtime — the JVM can confirm "a M
   * p" but not "a Map of UUID to
   * SourceRefDTO". Suppressed rather than handled because both ends of the cont
   * act are ours:
   * {@code AgentService.options} puts this exact map in, under {@link #RET
   * IEVED}.
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
