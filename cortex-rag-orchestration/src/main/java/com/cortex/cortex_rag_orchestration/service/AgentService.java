package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.AgentAnswerDTO;
import com.cortex.cortex_common.dto.AnswerSegmentDTO;
import com.cortex.cortex_common.dto.SourceRefDTO;
import com.cortex.cortex_rag_orchestration.util.SegmentParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Service;

/**
 * The agent loop: ask the model, run any tool it asks for, ask again, stop when
 * it answers.
 *
 * <p>
 * Stateless by design. It is handed the conversation and never loads or saves
 * one, so Slice 2's
 * conversation layer can replay prior turns into the same method without
 * changing anything here.
 *
 * <p>
 * Citations work as "ids inside the loop, numbers at the edge": the tools show
 * the model each
 * chunk's real id, the model cites ids, and this class renumbers them 1..N once
 * the answer arrives.
 * Numbering cannot be done while searching, because {@link SourceFormatter}
 * restarts at 1 on every
 * search and several searches can happen in one round.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

  private final ChatModel chatModel;

  private final ToolCallingManager toolCallingManager;

  private final LibraryTools libraryTools;

  private final AttachmentLoader attachmentLoader;

  private final ObjectMapper objectMapper;

  /**
   * One model call per second. Shared with nothing — the old chat path keeps its
   * own.
   */
  private final RateLimiter inferenceRateLimiter = RateLimiter.create(1);

  /**
   * Safety cap on tool-execution rounds. The framework's own automatic loop is
   * recursive and
   * uncapped, so this is ours to enforce whichever way the loop is driven.
   */
  private static final int MAX_ITERATIONS = 5;

  /** Shared by every call so the final answer at the cap is made the same way as any other. */
  private static final double TEMPERATURE = 0.3;

  private static final String GAVE_UP = "I wasn't able to finish looking into that. Please try asking more specifically.";

  /** Sent at the cap, with the tools taken away, so the turn ends in an answer rather than nothing. */
  private static final String ANSWER_NOW = """
      You've used all the searches available for this question. Answer now, using only what's \
      already in front of you: the conversation, any attached files, and the tool results above. If \
      something couldn't be found, say what you looked for and that it wasn't there.""";

  /**
   * Agent policy: how to behave. What exists is described by the tools
   * themselves, and nothing here
   * mentions scope — reaching for search_library <em>is</em> broadening, and the
   * caller narrates it.
   *
   * <p>
   * Retrieval is conditional on purpose. The earlier version said "always search
   * before you
   * answer" and "answer using ONLY what the tool returns", which forced a lookup
   * for "can you
   * rephrase that?" and forbade answering from an earlier turn — making
   * multi-turn impossible.
   *
   * <p>
   * Written to explain the reason behind each rule rather than shout it, and with
   * worked examples — the Anthropic and Gemini prompting guides both name these
   * as the most reliable levers. The version before this stacked three "search
   * before you conclude" lines against one weak line about the conversation, and
   * the model searched the library five times for "why did you say that?".
   *
   * <p>
   * The examples cite {@code <Source id>} as a placeholder. If the model ever
   * copies it literally, {@link CitationNumberer} drops it and the dropped-cite
   * warning shows it.
   */
  private static final String SYSTEM_INSTRUCTIONS = """
      You are Cortex. You're in a conversation with a user about their media library: videos and
      audio they uploaded, which have been transcribed and described. You can't see the library
      directly; you reach it through tools.

      <how_to_respond>
      Every message is part of an ongoing conversation. Before doing anything, work out what this
      message is asking and where the answer is. Check these in order:

      1. The conversation. Many messages are about what's already been said: a follow-up, a request
         to shorten or rephrase, "?" or "huh?" meaning your last answer wasn't clear, a question about
         your own earlier answer, thanks, or "what can you do?". Answer these from the conversation.
         Don't search the library for them: it holds the user's recordings, not this conversation.

         People rarely say "earlier". They say "that", "him", "the second one", "the budget thing",
         "again", or use a name as if you both already know it. When a message points at something
         like this, find it in the turns you can see. If it isn't there and a note says earlier turns
         are hidden, call recall_conversation before searching the library. That's where the thing
         they mean is, and it's quicker than a search. If the first page doesn't have it, read the
         next one.

      2. Attached files. If the user attached files to this message, their content appears just
         before it. Answer from them when they contain the answer.

      3. The library. If the answer isn't in the conversation or the attached files, search. The
         attached files are only the part of the library the user pointed at, and the rest can still
         have the answer. So before telling the user something isn't there, search the library at
         least once.

      If a message is unclear and the conversation doesn't make it clear, ask one short question
      instead of guessing or searching.
      </how_to_respond>

      <tools>
      - search_library finds passages across the whole library. Search with the key words, not the
        whole sentence. If a search misses, try a different wording once or twice. If it still isn't
        there, say so. More searches with the same idea won't find it.
      - read_file reads one whole file when you have its fileId from an earlier result.
      - recall_conversation reads turns of this conversation that aren't shown. Use it when the user
        refers to something you can't find in the turns you can see.
      </tools>

      <history>
      Earlier turns show the tools you called while answering them, in order. Their results aren't
      kept between turns, so each one shows a note in its place. If you need a result again, call the
      tool again. A user message may say which files were attached to it; your answer to that message
      was based on those files. A user message marked "No answer was given" is one you didn't answer.
      It failed or the user stopped it, so read what follows it in that light.
      </history>

      <grounding>
      Answer only from the conversation, the attached files and what the tools return. Don't add
      outside knowledge. The user is asking about their own recordings, and an answer from general
      knowledge would look like it came from their files when it didn't.
      </grounding>

      <style>
      Write like a colleague who has watched the recordings and is telling the user what's in them.
      Use full sentences and give the context that makes a fact useful: who said it, what led up to
      it, what came right after, which recording, and roughly when (the passages give start times in
      seconds; say "about four minutes in", not "240.0"). A direct question usually takes two to four
      sentences. A summary or "tell me about" question can take a few short paragraphs. Don't pad:
      don't restate the question or open with filler.
      </style>

      <answer_format>
      Reply with a list of segments, each with "text" and "cites". In "cites", put the "Source id" of
      the passages that support that segment, copied exactly: only the one to three that most directly
      support it. A longer answer is more segments, each with its own cites, not one segment with many.
      A segment with no supporting passage, like a clarifying question or a reply about the conversation
      itself, has empty cites.

      The passages you get are transcripts and descriptions of the user's recordings. Talk about them
      as the recordings they are: "budget-review.mp4 doesn't mention Priya", "in the standup
      recording…". Don't say "the text", "the transcript", "the passages" or "the provided content".
      The user uploaded recordings, not text, and those words mean nothing to them.
      </answer_format>

      <examples>
      <example>
      User: what did they decide about the launch?
      You: [{"text": "They agreed to push the launch back to the second week of March.", "cites": ["<Source id>"]}]
      User: ?
      You: [{"text": "Sorry, that was too brief. In the planning call, the team agreed to move the launch from late February to the second week of March.", "cites": []},
            {"text": "The reason was testing: Dana said QA needed about two more weeks to finish regression runs, and nobody pushed back, so the new date stood.", "cites": []}]
      </example>

      <example>
      (Attached: standup-0915.mp4, which never mentions Priya.)
      User: what did Priya say about the budget?
      You: call search_library with "Priya budget"
      Result: a passage from budget-review.mp4 where Priya talks about the Q3 budget.
      You: [{"text": "Priya isn't in standup-0915.mp4, but she talks about the budget in budget-review.mp4.", "cites": []},
            {"text": "About six minutes in, she says the Q3 budget is frozen until October while finance reconciles the overspend from the summer campaign.", "cites": ["<Source id>"]},
            {"text": "She adds that anything already approved can go ahead, but new requests will wait until the freeze lifts.", "cites": ["<Source id>"]}]
      </example>

      <example>
      User: what did Marcus do?
      You: [{"text": "I searched your library and couldn't find anyone called Marcus. Is the name spelled differently in the recording?", "cites": []}]
      User: why couldn't you find him?
      You: [{"text": "I searched for \\"Marcus\\" and nothing in your library matched. Transcripts spell names the way they sound, so if it's written differently in the recording, say \\"Markus\\", the search wouldn't find it. Try asking with another spelling, or tell me something else he talked about and I'll search for that.", "cites": []}]
      </example>

      <example>
      User: who presented in the all-hands?
      You: [{"text": "Two people presented in the all-hands. Ana opened with hiring: three open roles, and a new onboarding buddy system starting next month.", "cites": ["<Source id>"]},
            {"text": "Joel followed with the roadmap and walked through what's planned for the next two quarters.", "cites": ["<Source id>"]}]
      User: what did the second one say about Q4?
      You: call search_library with "Joel roadmap Q4"
      </example>

      <example>
      (A note says earlier turns are hidden. None of the turns shown mention a delay.)
      User: did he ever explain why it slipped?
      You: call recall_conversation with page 0
      Result: an earlier turn where you told the user Joel announced the mobile app release was
      delayed to Q1.
      You: call search_library with "Joel mobile app delay reason"
      </example>
      </examples>
      """;

  /**
   * Built with the genai Schema builder like {@code VertexAnswerGenerator}'s,
   * then serialised —
   * Spring AI's options take the schema as a JSON string. Letting the SDK write
   * that JSON avoids
   * hand-guessing its exact form. If the builder turns out to accept a
   * {@code Schema} directly,
   * drop the {@code toJson()} and the field type.
   */
  private static final String RESPONSE_SCHEMA = Schema.builder()
      .type(Type.Known.ARRAY)
      .items(Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(Map.of(
              "text", Schema.builder().type(Type.Known.STRING).build(),
              "cites",
              Schema.builder()
                  .type(Type.Known.ARRAY)
                  .items(Schema.builder()
                      // STRING, not INTEGER as in VertexAnswerGenerator: the agent cites chunk
                      // ids, and they only become numbers after resolveCitations.
                      .type(Type.Known.STRING)
                      .build())
                  .build()))
          .required(List.of("text", "cites"))
          .build())
      .build()
      .toJson();

  /**
   * The model's raw answer, before cites are validated and renumbered. Not
   * private: Jackson has to
   * reach the canonical constructor to deserialise it.
   */
  record RawSegment(String text, List<String> cites) {
  }

  /**
   * @param conversation   prior turns, if any, then the question — the last
   *                       message must be the
   *                       user's question
   * @param conversationId the stored conversation this question belongs to, so
   *                       the recall tool
   *                       can read its earlier turns; null on the stateless path,
   *                       where there is nothing to recall
   */
  public AgentAnswerDTO answer(List<Message> conversation, String userId, List<UUID> fileIds,
      UUID conversationId) {

    // Tools add to this as they search; we read it once the answer is in.
    // Concurrent because
    // Spring AI's own source says tool execution is only "currently" synchronous.
    Map<UUID, SourceRefDTO> retrieved = new ConcurrentHashMap<>();

    LoadedAttachments attachments = attachmentLoader.load(
        fileIds, lastQuestion(conversation), userId, retrieved);

    Prompt prompt = new Prompt(withContext(conversation, attachments),
        options(userId, conversationId, retrieved));

    ChatResponse response = call(prompt);

    int iterations = 0;

    int toolCallsCount = 0;

    while (response.hasToolCalls()) {

      if (++iterations > MAX_ITERATIONS) {
        // Two numbers on purpose: what this request actually cost, and what the model
        // was still
        // asking for when we cut it off. The second is the tool-call flood signal (§8.9
        // tripwire).
        log.warn("[agent] hit the {}-iteration cap; {} tool call(s) executed, {} more requested; giving up",
            MAX_ITERATIONS, toolCallsCount, response.getResult().getOutput().getToolCalls().size());
        return gaveUp();
      }

      // Runs every requested tool and returns the conversation with the request AND
      // the result already appended — the bookkeeping we did by hand before.
      ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, response);

      toolCallsCount += response.getResult().getOutput().getToolCalls().size();

      // Options must be carried over, or the next call goes out with no tools and no
      // user id.
      prompt = new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions());

      response = call(prompt);
    }

    String text = response.getResult().getOutput().getText();

    // Rounds and calls are different numbers: one round can contain several tool
    // calls (§11 V4).
    // Logging only rounds undercounts the work, and hides a flood.
    log.info("[agent] answered after {} tool round(s), {} tool call(s), {} chars",
        iterations, toolCallsCount, text == null ? 0 : text.length());

    return resolveCitations(text, retrieved);
  }

  /**
   * The same loop, reporting as it goes instead of at the end.
   *
   * <p>
   * Steps, sources and segments all leave through {@code onEvent} as they
   * happen. A callback rather than the response object, so Slice 2's conversation
   * layer can sit in
   * between and persist segments while they stream.
   *
   * @return true when the model produced its answer, false when the loop was
   *         stopped by its iteration cap — the same fact as
   *         {@code AgentAnswerDTO.hitIterationCap} on the blocking path,
   *         inverted.
   *         Either way the callback has already received everything that was
   *         sent.
   *         The caller needs this because a capped run returns normally, and
   *         whatever streamed before the cap is a half answer, not a finished
   *         one.
   */
  public boolean answerStream(List<Message> conversation, String userId, List<UUID> fileIds,
      UUID conversationId, Consumer<AgentEvent> onEvent) {

    Map<UUID, SourceRefDTO> retrieved = new ConcurrentHashMap<>();
    CitationNumberer numberer = new CitationNumberer(retrieved);

    boolean hasAttachments = fileIds != null && !fileIds.isEmpty();

    if (hasAttachments) {
      onEvent.accept(new StepEvent("Reading attached context…"));
    }

    LoadedAttachments attachments = attachmentLoader.load(
        fileIds, lastQuestion(conversation), userId, retrieved);

    Prompt prompt = new Prompt(withContext(conversation, attachments),
        options(userId, conversationId, retrieved));

    SegmentParser<RawSegment> parser = new SegmentParser<>(objectMapper, RawSegment.class,
        raw -> emitSegment(raw, numberer, onEvent));

    int iterations = 0;

    int toolCallsCount = 0;

    while (true) {
      ChatResponse toolCall = streamRound(prompt, parser, onEvent);

      if (toolCall == null) {
        break; // that round produced the answer, not a tool request
      }

      List<ToolCall> calls = toolCall.getResult().getOutput().getToolCalls();

      if (++iterations > MAX_ITERATIONS) {
        log.warn("[agent] hit the {}-iteration cap; {} tool call(s) executed, {} more requested; "
            + "answering from what was gathered", MAX_ITERATIONS, toolCallsCount, calls.size());
        return answerAtCap(prompt, parser, onEvent, iterations - 1, toolCallsCount);
      }

      // Told before the tools run, so the wait is legible while it happens. Every
      // call is recorded; only some are shown — recording and showing are separate.
      int round = iterations;
      for (ToolCall call : calls) {
        onEvent.accept(new ToolCallEvent(round, call.name(), call.arguments()));
        stepLabel(call, hasAttachments).ifPresent(label -> onEvent.accept(new StepEvent(label)));
      }

      toolCallsCount += calls.size();

      ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCall);

      prompt = new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions());
    }

    warnOnDropped(numberer);

    log.info("[agent] streamed answer after {} tool round(s), {} tool call(s)", iterations, toolCallsCount);

    return true;
  }

  /**
   * One last call, with no tools declared, so the model has to answer from what it
   * already gathered instead of the turn ending with nothing (plan §8.9).
   *
   * <p>
   * {@code prompt} holds the history up to the last round that actually ran: the
   * request that tripped the cap was never executed, so it was never appended.
   * Spring AI 1.1.2 has no way to set Gemini's function-calling mode, so leaving
   * the tools off the request is the only way to make a call impossible.
   *
   * @return true if the model answered — a real answer on real evidence, so the
   *         caller saves it; false if it somehow still asked for a tool
   */
  private boolean answerAtCap(Prompt prompt, SegmentParser<RawSegment> parser,
      Consumer<AgentEvent> onEvent, int rounds, int toolCallsCount) {

    List<Message> messages = new ArrayList<>(prompt.getInstructions());
    messages.add(new UserMessage(ANSWER_NOW));

    ChatResponse toolCall = streamRound(new Prompt(messages, finalOptions()), parser, onEvent);

    if (toolCall != null) {
      log.warn("[agent] asked for a tool with none declared; giving up");
      // Segments may already have gone out. Say it stopped early rather than let the
      // partial answer look finished.
      onEvent.accept(new StepEvent(GAVE_UP));
      return false;
    }

    log.info("[agent] answered at the cap after {} tool round(s), {} tool call(s)", rounds, toolCallsCount);

    return true;
  }

  /**
   * Consumes one streamed round.
   *
   * @return the response carrying tool calls, or null if this round was the
   *         answer — which is only
   *         knowable once the round is over, so every call streams.
   */
  private ChatResponse streamRound(Prompt prompt, SegmentParser<RawSegment> parser,
      Consumer<AgentEvent> onEvent) {

    inferenceRateLimiter.acquire();

    ChatResponse toolCall = null;
    StringBuilder raw = new StringBuilder();
    boolean parseFailed = false;

    for (ChatResponse response : chatModel.stream(prompt).toIterable()) {

      if (response.getResult() == null) {
        continue;
      }

      if (response.hasToolCalls()) {
        // Gemini sends a function call as one complete chunk, never in fragments (§11
        // V4).
        toolCall = response;
        continue;
      }

      String piece = response.getResult().getOutput().getText();

      if (piece == null || piece.isEmpty()) {
        continue;
      }

      raw.append(piece);

      if (parseFailed) {
        continue;
      }

      try {
        parser.feed(piece);
      } catch (Exception e) {
        // Degrade rather than drop the answer: stop parsing, keep collecting, and hand
        // over
        // whatever arrived as one uncited segment below.
        log.warn("[agent] streamed answer was not the expected JSON; sending it uncited", e);
        parseFailed = true;
      }
    }

    if (parseFailed) {
      onEvent.accept(new SegmentEvent(segment(raw.toString(), List.of())));
    }

    return toolCall;
  }

  /**
   * Sources go out before the segment citing them — the ordering the frontend
   * relies on.
   */
  private void emitSegment(RawSegment raw, CitationNumberer numberer, Consumer<AgentEvent> onEvent) {
    List<Integer> cites = numbered(raw, numberer);

    numberer.drainNewSources().forEach(source -> onEvent.accept(new SourceEvent(source)));

    onEvent.accept(new SegmentEvent(segment(raw.text(), cites)));
  }

  /**
   * The broadening is narrated by code, from state we hold — the model is never
   * told about scope,
   * so it has nothing to forget or misreport. With attachments present, reaching
   * for the library
   * <em>is</em> widening beyond them.
   *
   * <p>
   * Only reading and searching are shown. Anything else — looking back through the
   * conversation included — is the model working out its answer, and the user
   * sees "Thinking…" for it, which the frontend shows whenever no step is running.
   */
  private Optional<String> stepLabel(ToolCall call, boolean hasAttachments) {
    return switch (call.name()) {
      case "search_library" -> Optional.of(hasAttachments
          ? "Not in your attached files — searching the whole library…"
          : "Searching library…");
      case "read_file" -> Optional.of("Reading file…");
      default -> Optional.empty();
    };
  }

  /**
   * Turns the model's cited ids into the numbered form the frontend reads.
   *
   * <p>
   * Ids the tools never returned are dropped — the model cannot cite something it
   * was not shown.
   * That check is identity, which is stricter than the old path's "is this
   * integer within range",
   * where an invented number lands on a real but unrelated source.
   */
  private AgentAnswerDTO resolveCitations(String json, Map<UUID, SourceRefDTO> retrieved) {

    if (json == null || json.isBlank()) {
      return new AgentAnswerDTO(List.of(), List.of(), false);
    }

    List<RawSegment> rawSegments = parse(json);

    if (rawSegments == null) {
      // Not the shape we asked for. Keep the words — an unciteable answer beats no
      // answer.
      return new AgentAnswerDTO(List.of(segment(json, List.of())), List.of(), false);
    }

    CitationNumberer numberer = new CitationNumberer(retrieved);
    List<AnswerSegmentDTO> answer = new ArrayList<>();

    for (RawSegment raw : rawSegments) {
      answer.add(segment(raw.text(), numbered(raw, numberer)));
    }

    warnOnDropped(numberer);

    // Numbers were assigned in answer order, so draining once gives the sources in
    // that order.
    return new AgentAnswerDTO(answer, numberer.drainNewSources(), false);
  }

  /**
   * Cites for one segment: in order, de-duplicated, ids we never showed the model
   * dropped.
   */
  private List<Integer> numbered(RawSegment raw, CitationNumberer numberer) {
    List<Integer> cites = new ArrayList<>();

    for (String cite : raw.cites() == null ? List.<String>of() : raw.cites()) {
      numberer.numberFor(cite).ifPresent(sourceNo -> {
        if (!cites.contains(sourceNo)) {
          cites.add(sourceNo);
        }
      });
    }

    return cites;
  }

  /**
   * Mangled or invented ids are the main risk of citing by id, and a dropped cite
   * is otherwise
   * silent. This is the evidence for switching to a shorter, more copyable id
   * form.
   */
  private void warnOnDropped(CitationNumberer numberer) {
    List<String> dropped = numberer.dropped();

    if (!dropped.isEmpty()) {
      log.warn("[agent] dropped {} cite(s) that match no retrieved chunk: {}", dropped.size(), dropped);
    }
  }

  /**
   * Null when the text isn't the shape we asked for — the caller keeps the answer
   * regardless.
   */
  private List<RawSegment> parse(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<List<RawSegment>>() {
      });
    } catch (Exception e) {
      log.warn("[agent] answer was not the expected JSON; returning it uncited. Text: {}", json, e);
      return null;
    }
  }

  private AnswerSegmentDTO segment(String text, List<Integer> cites) {
    return AnswerSegmentDTO.builder().text(text).cites(cites).build();
  }

  private AgentAnswerDTO gaveUp() {
    return new AgentAnswerDTO(List.of(segment(GAVE_UP, List.of())), List.of(), true);
  }

  private ChatResponse call(Prompt prompt) {
    inferenceRateLimiter.acquire();
    return chatModel.call(prompt);
  }

  /**
   * System policy, then the prior turns, then any attached content, then the
   * question.
   *
   * <p>
   * Attachments go in as their own message rather than into {@code conversation},
   * because they
   * belong to this turn only. Slice 2 replays prior questions and answers; it
   * must never replay
   * content the user attached three messages ago. The block says "files attached
   * to this message", so it sits directly before that message — not before the
   * history.
   *
   * <p>
   * With a one-message conversation and no attachments this is byte-identical to
   * what came before.
   */
  private List<Message> withContext(List<Message> conversation, LoadedAttachments attachments) {
    List<Message> messages = new ArrayList<>();

    messages.add(new SystemMessage(SYSTEM_INSTRUCTIONS));

    int question = conversation.size() - 1;

    messages.addAll(conversation.subList(0, question));

    if (!attachments.isEmpty()) {
      messages.add(new UserMessage(attachmentMessage(attachments)));
    }

    messages.add(conversation.get(question));

    return messages;
  }

  /**
   * The attached content as one block: a header naming the files, the chunks, any
   * notes, and a closing line. The Gemini prompting guide recommends a clear
   * transition after a long block of context and before the question; without
   * one, the question can read as part of the attachment.
   */
  private String attachmentMessage(LoadedAttachments attachments) {
    StringBuilder message = new StringBuilder();

    if (attachments.fileNames().isEmpty()) {
      // Nothing fit or nothing was available — only notes follow.
      message.append("Files attached to this message could not be included:\n\n");
    } else {
      message.append("Files attached to this message: ")
          .append(String.join(", ", attachments.fileNames()))
          .append("\n\n")
          .append(attachments.text());
    }

    // Told plainly, so the model can act on it — a file missing without explanation
    // is worse than
    // one it knows it cannot see.
    attachments.notes().forEach(note -> message.append("Note: ").append(note).append("\n"));

    message.append("\nEnd of attached files. The user's message follows.");

    return message.toString();
  }

  /** What to search for if an attachment is too big to read whole. */
  private String lastQuestion(List<Message> conversation) {
    for (int i = conversation.size() - 1; i >= 0; i--) {
      if (conversation.get(i) instanceof UserMessage user) {
        return user.getText();
      }
    }

    return "";
  }

  /**
   * Rebuilt per call: the tool list is fixed, but the tool context carries this
   * request's user id
   * and its own retrieved map, so the options object cannot be shared between
   * requests.
   */
  private GoogleGenAiChatOptions options(String userId, UUID conversationId,
      Map<UUID, SourceRefDTO> retrieved) {

    // Not Map.of: conversationId is absent on the stateless path, and Map.of
    // rejects
    // nulls.
    Map<String, Object> toolContext = new HashMap<>();
    toolContext.put(LibraryTools.USER_ID, userId);
    toolContext.put(LibraryTools.RETRIEVED, retrieved);
    if (conversationId != null) {
      toolContext.put(LibraryTools.CONVERSATION_ID, conversationId);
    }

    return GoogleGenAiChatOptions.builder()
        // Low, on purpose. The default is tuned for varied prose, but the decisions
        // this loop
        // depends on — "is the attached content enough, or do I search?" — should not
        // be a coin
        // flip. At the default it escalated on 4 of 5 identical requests.
        .temperature(TEMPERATURE)
        .toolCallbacks(ToolCallbacks.from(libraryTools))
        .toolContext(toolContext)
        .internalToolExecutionEnabled(false)
        .responseMimeType("application/json")
        .responseSchema(RESPONSE_SCHEMA)
        .build();
  }

  /**
   * The same answer settings as {@link #options}, with no tools. Used for the one
   * call made at the cap: with nothing declared, the model cannot ask for a tool
   * and has to answer. No tool context either — nothing can run to read it.
   */
  private GoogleGenAiChatOptions finalOptions() {
    return GoogleGenAiChatOptions.builder()
        .temperature(TEMPERATURE)
        .internalToolExecutionEnabled(false)
        .responseMimeType("application/json")
        .responseSchema(RESPONSE_SCHEMA)
        .build();
  }
}
