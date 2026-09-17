package com.cortex.cortex_rag_orchestration.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cortex.cortex_common.dto.ChatAnswerDTO;
import com.cortex.cortex_common.dto.ChatQuestionDTO;
import com.cortex.cortex_common.dto.AgentAnswerDTO;
import com.cortex.cortex_rag_orchestration.service.AgentEvent;
import com.cortex.cortex_rag_orchestration.service.AgentService;
import com.cortex.cortex_rag_orchestration.service.ChatService;
import com.cortex.cortex_rag_orchestration.service.SegmentEvent;
import com.cortex.cortex_rag_orchestration.service.SourceEvent;
import com.cortex.cortex_rag_orchestration.service.StepEvent;

import java.io.IOException;
import java.util.Map;

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
        List.of(new UserMessage(question.getQuestion())), userId, question.getFileIds());

    return ResponseEntity.ok(answer);
  }

  /**
   * The agentic path, streamed. Same answer as {@link #agentChat}, reported as it happens: a
   * {@code step} per tool call, then sources and segments, then {@code done}.
   *
   * <p>Three minutes rather than the older path's sixty seconds — an agent makes several model
   * calls, and the emitter cuts off silently when it expires.
   */
  @PostMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter agentStream(@Valid @RequestBody ChatQuestionDTO question,
      Authentication authentication) {

    String userId = authentication.getName();
    SseEmitter emitter = new SseEmitter(180_000L);

    Thread.startVirtualThread(() -> {
      try {
        agentService.answerStream(
            List.of(new UserMessage(question.getQuestion())), userId, question.getFileIds(),
            event -> send(emitter, event));

        // An explicit end, so the client can tell "finished" from "connection dropped".
        send(emitter, "done", Map.of());
        emitter.complete();
      } catch (Exception e) {
        log.error("Error streaming agent answer for question: {}", question.getQuestion(), e);
        emitter.completeWithError(e);
      }
    });

    return emitter;
  }

  /** Sealed event type, so the compiler checks every kind has a place on the wire. */
  private void send(SseEmitter emitter, AgentEvent event) {
    switch (event) {
      case StepEvent step -> send(emitter, "step", Map.of("label", step.label()));
      case SourceEvent source -> send(emitter, "source", source.source());
      case SegmentEvent segment -> send(emitter, "segment", segment.segment());
    }
  }

  private void send(SseEmitter emitter, String eventName, Object data) {
    try {
      emitter.send(SseEmitter.event().name(eventName).data(data));
    } catch (IOException e) {
      throw new RuntimeException("Error sending SSE event: " + eventName, e);
    }
  }
}
