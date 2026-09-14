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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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

  private static final String GAVE_UP = "I wasn't able to finish looking into that. Please try asking more specifically.";

  /**
   * Agent policy: how to behave. What exists is described by the tools
   * themselves.
   */
  private static final String SYSTEM_INSTRUCTIONS = """
      You are Cortex, an assistant that answers questions about a user's media library.
      You cannot see the library directly. The only way to find anything is the search_library tool,
      so always search before you answer.
      Answer using ONLY what the tool returns. If it returns nothing relevant, say you don't know.
      Never use outside knowledge.
      Break your answer into segments. For each segment, put the "Source id" values of the chunks
      it came from in that segment's cites, copied exactly. Use an empty list if nothing supports it.
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

  public AgentAnswerDTO answer(List<Message> conversation, String userId) {

    // Tools add to this as they search; we read it once the answer is in.
    // Concurrent because
    // Spring AI's own source says tool execution is only "currently" synchronous.
    Map<UUID, SourceRefDTO> retrieved = new ConcurrentHashMap<>();

    Prompt prompt = new Prompt(withSystemInstructions(conversation), options(userId, retrieved));

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
   * Nothing is returned: steps, sources and segments all leave through
   * {@code onEvent} as they
   * happen. A callback rather than the response object, so Slice 2's conversation
   * layer can sit in
   * between and persist segments while they stream.
   */
  public void answerStream(List<Message> conversation, String userId, Consumer<AgentEvent> onEvent) {

    Map<UUID, SourceRefDTO> retrieved = new ConcurrentHashMap<>();
    CitationNumberer numberer = new CitationNumberer(retrieved);

    Prompt prompt = new Prompt(withSystemInstructions(conversation), options(userId, retrieved));

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
        log.warn("[agent] hit the {}-iteration cap; {} tool call(s) executed, {} more requested; giving up",
            MAX_ITERATIONS, toolCallsCount, calls.size());
        // Segments may already have gone out. Say it stopped early rather than let the
        // partial answer look finished.
        onEvent.accept(new StepEvent(GAVE_UP));
        return;
      }

      // Told to the user before the tools run, so the wait is legible while it
      // happens.
      calls.forEach(call -> onEvent.accept(new StepEvent(stepLabel(call))));

      toolCallsCount += calls.size();

      ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCall);

      prompt = new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions());
    }

    warnOnDropped(numberer);

    log.info("[agent] streamed answer after {} tool round(s), {} tool call(s)", iterations, toolCallsCount);
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

  private String stepLabel(ToolCall call) {
    return switch (call.name()) {
      case "search_library" -> "Searching library…";
      case "read_file" -> "Reading file…";
      default -> "Working…";
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

  private List<Message> withSystemInstructions(List<Message> conversation) {
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(SYSTEM_INSTRUCTIONS));
    messages.addAll(conversation);
    return messages;
  }

  /**
   * Rebuilt per call: the tool list is fixed, but the tool context carries this
   * request's user id
   * and its own retrieved map, so the options object cannot be shared between
   * requests.
   */
  private GoogleGenAiChatOptions options(String userId, Map<UUID, SourceRefDTO> retrieved) {
    return GoogleGenAiChatOptions.builder()
        .toolCallbacks(ToolCallbacks.from(libraryTools))
        .toolContext(Map.of(
            LibraryTools.USER_ID, userId,
            LibraryTools.RETRIEVED, retrieved))
        .internalToolExecutionEnabled(false)
        .responseMimeType("application/json")
        .responseSchema(RESPONSE_SCHEMA)
        .build();
  }
}
