package com.cortex.cortex_rag_orchestration.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cortex.cortex_common.dto.ChatAnswerDTO;
import com.cortex.cortex_common.dto.ChatQuestionDTO;
import com.cortex.cortex_common.dto.AgentAnswerDTO;
import com.cortex.cortex_rag_orchestration.service.AgentService;
import com.cortex.cortex_rag_orchestration.service.ChatService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

import org.springframework.ai.chat.messages.UserMessage;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chats")
public class ChatController {

  private final ChatService chatService;

  private final AgentService agentService;

  @PostMapping()
  public ResponseEntity<ChatAnswerDTO> chat(@Valid @RequestBody ChatQuestionDTO question,
      Authentication authentication) {

    String userId = authentication.getName();

    ChatAnswerDTO answer = chatService.generateAnswer(question, userId);

    return ResponseEntity.ok(answer);
  }

  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamChat(@Valid @RequestBody ChatQuestionDTO question, Authentication authentication) {
    String userId = authentication.getName();
    SseEmitter emitter = new SseEmitter(60_000L); // 60 seconds timeout

    Thread.startVirtualThread(() -> {
      try {
        chatService.streamAnswer(question, userId, emitter);
        emitter.complete();
      } catch (Exception e) {
        emitter.completeWithError(e);
      }
    });

    return emitter;
  }


  /**
   * The agentic path. Same auth and body as {@link #chat}, but the answer is planned: the agent
   * searches, judges what came back, and may search again before answering.
   *
   * <p>Slice 1 sends a single question. The conversation is a list because Slice 2's conversation
   * layer will prepend prior turns to the identical call.
   */
  @PostMapping("/agent")
  public ResponseEntity<AgentAnswerDTO> agentChat(@Valid @RequestBody ChatQuestionDTO question,
      Authentication authentication) {

    String userId = authentication.getName();

    AgentAnswerDTO answer = agentService.answer(
        List.of(new UserMessage(question.getQuestion())), userId);

    return ResponseEntity.ok(answer);
  }
}
