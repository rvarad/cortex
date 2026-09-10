package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.AgentAnswerDTO;
import com.google.common.util.concurrent.RateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

  private final ChatModel chatModel;

  private final ToolCallingManager toolCallingManager;

  private final LibraryTools libraryTools;

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
      """;

  public AgentAnswerDTO answer(List<Message> conversation, String userId) {

    Prompt prompt = new Prompt(withSystemInstructions(conversation), options(userId));

    ChatResponse response = call(prompt);

    int iterations = 0;

    int toolCallsCount = 0;

    while (response.hasToolCalls()) {

      if (++iterations > MAX_ITERATIONS) {
        // Two numbers on purpose: what this request actually cost, and what the model was still
        // asking for when we cut it off. The second is the tool-call flood signal (§8.9 tripwire).
        log.warn("[agent] hit the {}-iteration cap; {} tool call(s) executed, {} more requested; giving up",
            MAX_ITERATIONS, toolCallsCount, response.getResult().getOutput().getToolCalls().size());
        return new AgentAnswerDTO(
            "I wasn't able to finish looking into that. Please try asking more specifically.", true);
      }

      // Runs every requested tool and returns the conversation with the request AND
      // the result
      // already appended — the bookkeeping we did by hand before.
      ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, response);

      toolCallsCount += response.getResult().getOutput().getToolCalls().size();

      // Options must be carried over, or the next call goes out with no tools and no
      // user id.
      prompt = new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions());

      response = call(prompt);
    }

    String text = response.getResult().getOutput().getText();

    // Rounds and calls are different numbers: one round can contain several tool calls (§11 V4).
    // Logging only rounds undercounts the work, and hides a flood.
    log.info("[agent] answered after {} tool round(s), {} tool call(s), {} chars",
        iterations, toolCallsCount, text == null ? 0 : text.length());

    return new AgentAnswerDTO(text, false);
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
   * request's user id,
   * so the options object cannot be shared between requests.
   */
  private GoogleGenAiChatOptions options(String userId) {
    return GoogleGenAiChatOptions.builder()
        .toolCallbacks(ToolCallbacks.from(libraryTools))
        .toolContext(Map.of(LibraryTools.USER_ID, userId))
        .internalToolExecutionEnabled(false)
        .build();
  }
}
