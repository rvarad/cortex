package com.cortex.cortex_rag_orchestration.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cortex.cortex_common.dto.ChatAnswerDTO;
import com.cortex.cortex_common.dto.ChatQuestionDTO;
import com.cortex.cortex_rag_orchestration.service.ChatService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatController {

  private final ChatService chatService;

  @PostMapping()
  public ResponseEntity<ChatAnswerDTO> chat(@Valid @RequestBody ChatQuestionDTO question,
      Authentication authentication) {

    String userId = authentication.getName();

    ChatAnswerDTO answer = chatService.generateAnswer(question, userId);

    return ResponseEntity.ok(answer);
  }

}
