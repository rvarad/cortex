package com.cortex.cortex_rag_orchestration.controller;

import com.cortex.cortex_common.dto.ChatQuestionDTO;
import com.cortex.cortex_common.dto.ConversationDTO;
import com.cortex.cortex_common.dto.ConversationSummaryDTO;
import com.cortex.cortex_rag_orchestration.service.conversation.ConversationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chats that persist (plan §5.6). Every endpoint is scoped to the caller: another user's
 * conversation is a 404, the same as one that does not exist.
 *
 * <p>Starting a chat is two calls — create, then post the first message — so the id is known
 * before the stream begins and each endpoint does one job.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/conversations")
public class ConversationController {

  private final ConversationService conversationService;

  @PostMapping
  public ResponseEntity<ConversationSummaryDTO> create(Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(conversationService.create(authentication.getName()));
  }

  @GetMapping
  public ResponseEntity<List<ConversationSummaryDTO>> list(Authentication authentication) {
    return ResponseEntity.ok(conversationService.list(authentication.getName()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ConversationDTO> get(@PathVariable UUID id, Authentication authentication) {
    return ResponseEntity.ok(conversationService.get(id, authentication.getName()));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
    conversationService.delete(id, authentication.getName());
    return ResponseEntity.noContent().build();
  }

  /**
   * Asks a question in this conversation and streams the answer: a {@code step} per tool call,
   * then sources and segments, then {@code done}. Same events as the stateless
   * {@code /chats/agent/stream}, persisted on the way through.
   */
  @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter ask(@PathVariable UUID id, @Valid @RequestBody ChatQuestionDTO question,
      Authentication authentication) {

    String userId = authentication.getName();

    // Synchronously, so a wrong id is a plain 404 rather than a 200 stream that errors at once.
    conversationService.requireOwned(id, userId);

    // Three minutes: an agent makes several model calls, and the emitter cuts off silently when
    // it expires.
    SseEmitter emitter = new SseEmitter(180_000L);

    Thread.startVirtualThread(() -> {
      try {
        conversationService.ask(id, userId, question.getQuestion(), question.getFileIds(),
            event -> SseEvents.send(emitter, event));

        SseEvents.done(emitter);
        emitter.complete();
      } catch (Exception e) {
        log.error("Error answering in conversation {}: {}", id, question.getQuestion(), e);
        emitter.completeWithError(e);
      }
    });

    return emitter;
  }
}
