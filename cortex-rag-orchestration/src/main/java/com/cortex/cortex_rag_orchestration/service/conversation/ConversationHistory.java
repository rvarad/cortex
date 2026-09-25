package com.cortex.cortex_rag_orchestration.service.conversation;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;
import com.cortex.cortex_common.dto.SourceRefDTO;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_rag_orchestration.model.ConversationMessage;
import com.cortex.cortex_rag_orchestration.model.MessageRole;
import com.cortex.cortex_rag_orchestration.repository.ConversationMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two views the model gets of a stored conversation: the replay window that goes into every
 * prompt (plan §5.3), and what the {@code recall_conversation} tool hands back from before it
 * (§5.4).
 *
 * <p>Nothing here loads a whole conversation. Each view asks the database for a fixed number of
 * the newest rows — the limit <em>is</em> the window — and the recall view starts where the
 * replay view ends, so the model never reads the same turn twice. Both limits, and the offset
 * arithmetic between them, live in this one class so the two cannot drift apart.
 *
 * <p>Rows are paired into question-and-answer turns. A question that never got an answer — the
 * agent crashed, hit its cap before that was handled, or the user stopped it — is kept as a turn
 * with no answer and replayed with a marker, because the user saw it and their next message may be
 * about it ("?"). Because the limits count rows, not turns, a window can hold half a turn at its
 * edge; that half is dropped.
 *
 * <p>A stored answer with no text is treated the same way: its question is kept, marked as
 * unanswered, and the empty answer itself is never replayed. Gemini rejects a model turn with no
 * parts, so one such row would otherwise break every later question in the conversation. The write
 * path no longer saves them, but rows from before that fix still exist.
 *
 * <p>A turn carries what the model <em>did</em> as well as what it said: the files attached to the
 * question and the tools it called. Without them the model sees its own words with no reason
 * behind them, and cannot explain an earlier answer.
 */
@Slf4j
@Component
public class ConversationHistory {

  /**
   * One turn, oldest first in any list of them.
   *
   * @param attachedFiles display names of the files attached to the question, in the order
   *     attached; a deleted file appears as {@link #DELETED_FILE}
   * @param answer null when no answer was given
   * @param toolCalls the tools called to produce the answer, in order; empty when none were, and
   *     always empty when there is no answer
   */
  public record Turn(String question, List<String> attachedFiles, AssistantContent answer,
      List<RecordedToolCall> toolCalls) {

    boolean answered() {
      return answer != null;
    }
  }

  static final String NO_ANSWER_MARKER = "[No answer was given to this message.]";

  /**
   * What goes into the prompt ahead of the question.
   *
   * @param messages the replayed turns as messages, preceded by a note when {@code hasOlder}
   * @param hasOlder whether the conversation has rows older than the window — what the note and
   *     the recall tool exist for
   */
  public record Replay(List<Message> messages, boolean hasOlder) {
  }

  /**
   * Keyed on a reference the model can't resolve, not on the word "earlier" — people say "that",
   * "him", "the other one", almost never "earlier".
   */
  static final String OLDER_TURNS_NOTE = "This conversation has earlier turns that aren't shown "
      + "below. If the user refers to something you can't find in the turns shown (a name, \"that\", "
      + "\"the other one\"), it's probably in them: call recall_conversation.";

  /**
   * What an old tool call's result is replaced with. The result was never stored (F3); the call
   * stays so the model can see what it did. JSON, because Spring AI's Gemini adapter parses a tool
   * response as a JSON object. Same idea as Anthropic's "tool result clearing": keep the call,
   * replace the result with a note.
   */
  static final String RESULT_NOT_KEPT =
      "{\"note\": \"Result from an earlier turn, not kept. Call the tool again if you need it.\"}";

  static final String DELETED_FILE = "a file that has since been deleted";

  private final ConversationMessageRepository messageRepository;

  private final FileMetadataRepository fileMetadataRepository;

  private final ObjectMapper objectMapper;

  /** Rows replayed into every prompt. Eight rows is four turns when they line up. */
  private final int replayRows;

  /** Rows one recall call reads, from just before the replay window backwards. */
  private final int recallRows;

  public ConversationHistory(ConversationMessageRepository messageRepository,
      FileMetadataRepository fileMetadataRepository, ObjectMapper objectMapper,
      @Value("${chat.memory.replay-rows}") int replayRows,
      @Value("${chat.memory.recall-rows}") int recallRows) {
    this.messageRepository = messageRepository;
    this.fileMetadataRepository = fileMetadataRepository;
    this.objectMapper = objectMapper;
    this.replayRows = replayRows;
    this.recallRows = recallRows;
  }

  /**
   * The replay window for the next question. Called <em>before</em> that question is saved, so the
   * newest row is the last answer.
   *
   * <p>Asks for one row more than it will use: if that extra row comes back, older turns exist and
   * the model is told so. One query, no count.
   */
  public Replay replay(UUID conversationId, String userId) {
    List<ConversationMessage> newestFirst = messageRepository.findLatest(conversationId, replayRows + 1, 0);

    boolean hasOlder = newestFirst.size() > replayRows;

    List<ConversationMessage> window = oldestFirst(
        newestFirst.subList(0, Math.min(replayRows, newestFirst.size())));

    // true: the question being asked isn't saved yet, so a question at the end of this window is
    // the previous one, and if nothing follows it, it really went unanswered.
    return new Replay(messages(pair(window, fileNames(window, userId), true), hasOlder), hasOlder);
  }

  /**
   * The turns before the replay window, oldest first. Called by the tool while the question is
   * being answered, so the newest row is now that question — the offset skips it as well as the
   * window. {@code page} 0 is the block just before the window; each higher page is one block
   * further back.
   *
   * <p>Reads one extra row on the newer side: the first row of the replay window (page 0) or of
   * the previous page. It is used only to finish a turn split across that line — the block ends
   * with a question, and its answer is the next row over. Without it, that turn would be in
   * neither view: replay drops the answer as half a turn, and recall would drop the question. The
   * newer view always drops a leading answer, so a finished turn appears exactly once, here.
   */
  public List<Turn> recall(UUID conversationId, String userId, int page) {
    int offset = 1 + replayRows + Math.max(0, page) * recallRows;

    List<ConversationMessage> newestFirst =
        messageRepository.findLatest(conversationId, recallRows + 1, offset - 1);

    if (newestFirst.isEmpty()) {
      return List.of();
    }

    ConversationMessage newer = newestFirst.get(0); // the extra row, just across the line
    List<ConversationMessage> rows = oldestFirst(newestFirst.subList(1, newestFirst.size()));

    boolean splitTurn = !rows.isEmpty()
        && rows.get(rows.size() - 1).getRole() == MessageRole.USER
        && newer.getRole() == MessageRole.ASSISTANT;

    if (splitTurn) {
      rows = new ArrayList<>(rows);
      rows.add(newer);
    }

    // false: a question still at the end has no answer in this list, but may have one beyond it —
    // half a turn, not an unanswered one. (A split turn was just completed above.)
    return pair(rows, fileNames(rows, userId), false);
  }

  /**
   * Rows in order → turns. Pure apart from JSON reading, so testable without a database.
   *
   * <p>Walks the rows looking at one row, and the one after it:
   * <ul>
   *   <li>USER then ASSISTANT with text: a turn. Both rows are used, so move on two.
   *   <li>USER then ASSISTANT with no text: an unanswered turn; the empty answer is never replayed.
   *       Both rows are used, so move on two.
   *   <li>USER then another USER: an unanswered turn. Only the first row is used, so move on one —
   *       the second USER gets its own look.
   *   <li>ASSISTANT first: half a turn whose question fell outside this window. Move on one.
   * </ul>
   *
   * @param trailingQuestionIsOrphan whether a USER row with nothing after it went unanswered (true)
   *     or is half a turn whose answer lies beyond this list (false)
   */
  List<Turn> pair(List<ConversationMessage> rows, Map<UUID, String> fileNames,
      boolean trailingQuestionIsOrphan) {
    List<Turn> turns = new ArrayList<>();

    int i = 0;

    while (i < rows.size()) {
      ConversationMessage question = rows.get(i);

      if (question.getRole() != MessageRole.USER) {
        i += 1; // an answer whose question is outside this window
        continue;
      }

      ConversationMessage next = i + 1 < rows.size() ? rows.get(i + 1) : null;

      if (next == null) {
        if (trailingQuestionIsOrphan) {
          turns.add(unanswered(question, fileNames));
        }
        i += 1;
        continue;
      }

      if (next.getRole() != MessageRole.ASSISTANT) {
        turns.add(unanswered(question, fileNames)); // stopped or failed, then asked again
        i += 1;
        continue;
      }

      AssistantContent answer = assistantContent(next);

      if (answer.text().isBlank()) {
        // Replayed, this would be a model turn with no parts, which Gemini rejects — and it
        // would be rejected again on every later question. The question stays, unanswered.
        log.warn("[conversation] answer {} has no text; replaying its question as unanswered", next.getId());
        turns.add(unanswered(question, fileNames));
      } else {
        turns.add(new Turn(userText(question), attached(question, fileNames), answer, toolCalls(next)));
      }

      i += 2;
    }

    return turns;
  }

  private Turn unanswered(ConversationMessage question, Map<UUID, String> fileNames) {
    return new Turn(userText(question), attached(question, fileNames), null, List.of());
  }

  private List<String> attached(ConversationMessage question, Map<UUID, String> fileNames) {
    return question.getAttachedFileIds().stream()
        .map(fileId -> fileNames.getOrDefault(fileId, DELETED_FILE))
        .toList();
  }

  /**
   * Turns as prompt messages, with a note in front when older turns exist.
   *
   * <p>Each turn is rebuilt the way it happened: the question (noting any attachments), then each
   * round of tool calls as real function calls with a placeholder for each result, then the
   * answer. Real calls rather than text describing them, so there is no pattern for the model to
   * copy into its own answers — the only way to produce something that looks like a past call is
   * to make a call. A turn that used no tools replays as question then answer.
   *
   * <p>The note and the attachment line are on user messages, not a second system message: Spring
   * AI's Gemini model allows exactly one system message per prompt.
   *
   * <p>Without the note the model has no idea anything is missing and the recall tool is never
   * called. Same rule as the oversize-attachment note: a thing silently absent is worse than a
   * thing the model knows it cannot see.
   */
  List<Message> messages(List<Turn> turns, boolean hasOlder) {
    List<Message> messages = new ArrayList<>();

    if (hasOlder) {
      messages.add(new UserMessage(OLDER_TURNS_NOTE));
    }

    int callId = 0;

    for (Turn turn : turns) {
      if (!turn.answered()) {
        // The user saw this question go unanswered; their next message may be about exactly
        // that. Kept, marked, and nothing after it — there is no answer to replay.
        messages.add(new UserMessage(questionWithAttachments(turn) + "\n\n" + NO_ANSWER_MARKER));
        continue;
      }

      messages.add(new UserMessage(questionWithAttachments(turn)));

      for (List<RecordedToolCall> round : byRound(turn.toolCalls())) {
        List<ToolCall> calls = new ArrayList<>();
        List<ToolResponse> results = new ArrayList<>();

        for (RecordedToolCall call : round) {
          // The id only has to match a call to its response; Gemini pairs them by name and order.
          String id = "replay-" + (++callId);
          calls.add(new ToolCall(id, "function", call.name(), write(call.args())));
          results.add(new ToolResponse(id, call.name(), RESULT_NOT_KEPT));
        }

        messages.add(AssistantMessage.builder().content("").toolCalls(calls).build());
        messages.add(ToolResponseMessage.builder().responses(results).build());
      }

      messages.add(new AssistantMessage(turn.answer().text()));
    }

    return messages;
  }

  /**
   * Turns as the model reads them from the recall tool. Text rather than real calls: this is a
   * tool result, and the model does not copy tool results as its own style.
   *
   * <p>Deliberately carries {@code fileId} and {@code chunkIndex} but <em>no chunk id</em>: a cite
   * is only valid for a chunk retrieved in this request, so the model is given nothing citable to
   * copy. To cite something from an old turn it reads the file again (§5.4).
   */
  public String render(List<Turn> turns) {
    StringBuilder out = new StringBuilder();

    for (Turn turn : turns) {
      out.append("User: ").append(turn.question()).append('\n');

      if (!turn.attachedFiles().isEmpty()) {
        out.append("Attached: ").append(String.join(", ", turn.attachedFiles())).append('\n');
      }

      if (turn.toolCalls().isEmpty()) {
        out.append("Tools used: none\n");
      } else {
        out.append("Tools used:\n");
        for (RecordedToolCall call : turn.toolCalls()) {
          out.append("  ").append(call.name()).append(' ').append(write(call.args())).append('\n');
        }
      }

      if (!turn.answered()) {
        out.append("Assistant: (no answer was given)\n\n");
        continue;
      }

      out.append("Assistant: ");

      for (AnswerSegmentDTO segment : turn.answer().segments()) {
        out.append(segment.getText() == null ? "" : segment.getText().strip());
        if (segment.getCites() != null && !segment.getCites().isEmpty()) {
          out.append(" [sources ").append(join(segment.getCites())).append(']');
        }
        out.append(' ');
      }
      out.append('\n');

      if (!turn.answer().sources().isEmpty()) {
        out.append("Sources:");
        for (SourceRefDTO source : turn.answer().sources()) {
          out.append(" [").append(source.getSourceNo()).append("] ")
              .append(source.getFileDisplayName())
              .append(" (fileId: ").append(source.getFileId())
              .append(", chunk ").append(source.getChunkIndex())
              .append(", ").append(source.getStartTime()).append("-").append(source.getEndTime())
              .append("s)");
        }
        out.append('\n');
      }

      out.append('\n');
    }

    return out.toString();
  }

  /**
   * Display names for every file attached anywhere in these rows, one query. A file that is gone —
   * or was never this user's — is simply absent from the map.
   */
  public Map<UUID, String> fileNames(List<ConversationMessage> rows, String userId) {
    Set<UUID> ids = new HashSet<>();

    for (ConversationMessage row : rows) {
      ids.addAll(row.getAttachedFileIds());
    }

    Map<UUID, String> names = new HashMap<>();

    if (ids.isEmpty()) {
      return names;
    }

    for (FileMetadata file : fileMetadataRepository.findAllById(ids)) {
      if (userId.equals(file.getUserId())) {
        names.put(file.getId(), file.getFileDisplayName());
      }
    }

    return names;
  }

  /** The query returns newest first so LIMIT takes the tail; everything after reads in order. */
  static List<ConversationMessage> oldestFirst(List<ConversationMessage> newestFirst) {
    List<ConversationMessage> rows = new ArrayList<>(newestFirst);
    Collections.reverse(rows);
    return rows;
  }

  private String questionWithAttachments(Turn turn) {
    if (turn.attachedFiles().isEmpty()) {
      return turn.question();
    }
    return turn.question() + "\n\n[Attached to this message: "
        + String.join(", ", turn.attachedFiles()) + "]";
  }

  /** Calls grouped by the loop round they ran in, rounds in order, calls in order within each. */
  private List<List<RecordedToolCall>> byRound(List<RecordedToolCall> calls) {
    List<List<RecordedToolCall>> rounds = new ArrayList<>();
    int current = 0; // rounds start at 1; isEmpty() below handles the first call regardless

    for (RecordedToolCall call : calls) {
      if (rounds.isEmpty() || call.round() != current) {
        rounds.add(new ArrayList<>());
        current = call.round();
      }
      rounds.get(rounds.size() - 1).add(call);
    }

    return rounds;
  }

  private String join(List<Integer> cites) {
    StringBuilder joined = new StringBuilder();
    for (Integer cite : cites) {
      if (!joined.isEmpty()) {
        joined.append(", ");
      }
      joined.append(cite);
    }
    return joined.toString();
  }

  String userText(ConversationMessage row) {
    try {
      return objectMapper.readValue(row.getContent(), UserContent.class).text();
    } catch (Exception e) {
      throw new IllegalStateException("Unreadable USER content on message " + row.getId(), e);
    }
  }

  AssistantContent assistantContent(ConversationMessage row) {
    try {
      return objectMapper.readValue(row.getContent(), AssistantContent.class);
    } catch (Exception e) {
      throw new IllegalStateException("Unreadable ASSISTANT content on message " + row.getId(), e);
    }
  }

  List<RecordedToolCall> toolCalls(ConversationMessage row) {
    if (row.getToolCalls() == null || row.getToolCalls().isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(row.getToolCalls(), new TypeReference<List<RecordedToolCall>>() {
      });
    } catch (Exception e) {
      throw new IllegalStateException("Unreadable tool calls on message " + row.getId(), e);
    }
  }

  /** The model's raw argument JSON as a map, for storage. Unreadable arguments are kept as text. */
  Map<String, Object> arguments(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
      });
    } catch (Exception e) {
      log.warn("[conversation] tool arguments were not a JSON object; storing as text: {}", json);
      return Map.of("raw", json);
    }
  }

  String write(Object content) {
    try {
      return objectMapper.writeValueAsString(content);
    } catch (Exception e) {
      throw new IllegalStateException("Could not serialise message content", e);
    }
  }
}
