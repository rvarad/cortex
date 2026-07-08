package com.cortex.cortex_rag_orchestration.service;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;
import com.cortex.cortex_common.dto.ChatAnswerDTO;
import com.cortex.cortex_common.dto.ChatQuestionDTO;
import com.cortex.cortex_common.dto.SearchRequestDTO;
import com.cortex.cortex_common.dto.SearchResultDTO;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

  private final SearchService searchService;

  private final AnswerGenerator answerGenerator;

  private final String PROMPT = "You are Cortex, an assistant that answers questions about a user's media library. Answer the question using ONLY the information in the context below. If the answer is not in the context, say you don't know — do not use outside knowledge. Answer according to the schema provided. Break the answer into coherent segments, put the supporting \"Source no.\" integer/s in each segments \"cites\" (empty if none). Context: %s. Question: %s";

  private String contextAssembler(List<SearchResultDTO> searchResults) {
    StringBuilder context = new StringBuilder();

    for (int i = 0; i < searchResults.size(); i++) {
      SearchResultDTO result = searchResults.get(i);

      context.append("{\n").append("Source no.: ").append(i + 1).append("\n");
      context.append("fileName: ").append(result.getFileDisplayName()).append("\n");
      context.append("startTime: ").append(result.getStartTime()).append("\n");
      context.append("endTime: ").append(result.getEndTime()).append("\n");
      context.append("transcript: ").append(result.getTranscript()).append("\n");
      context.append("visualSummary: ").append(result.getVisualSummary()).append("\n");
      context.append("chunkIndex: ").append(result.getChunkIndex()).append("}\n\n");
    }

    return context.toString();
  }

  private List<AnswerSegmentDTO> sanitizeCites(List<AnswerSegmentDTO> answer, int sourceCount) {
    return answer.stream()
        .map(ans -> {
          List<Integer> cites = ans.getCites() == null ? List.of() : ans.getCites();
          return AnswerSegmentDTO.builder()
              .text(ans.getText())
              .cites(cites.stream()
                  .filter(Objects::nonNull)
                  .filter(c -> 1 <= c && c <= sourceCount)
                  .distinct()
                  .collect(Collectors.toList()))
              .build();
        })
        .collect(Collectors.toList());
  }

  private AnswerSegmentDTO sanitizeCites(AnswerSegmentDTO segment, int sourceCount) {
    List<Integer> cites = segment.getCites() == null ? List.of() : segment.getCites();
    return AnswerSegmentDTO.builder()
        .text(segment.getText())
        .cites(
            cites.stream()
                .filter(Objects::nonNull)
                .filter(c -> 1 <= c && c <= sourceCount)
                .distinct()
                .collect(Collectors.toList()))
        .build();
  }

  private void sendAnswer(SseEmitter emitter, String eventName, Object data) {
    try {
      emitter.send(SseEmitter.event().name(eventName).data(data));
    } catch (IOException e) {
      throw new RuntimeException("Error sending SSE event", e);
    }
  }

  public ChatAnswerDTO generateAnswer(ChatQuestionDTO question, String userId) {

    try {
      SearchRequestDTO request = SearchRequestDTO.builder()
          .query(question.getQuestion())
          .fileId(question.getFileId())
          .build();

      List<SearchResultDTO> searchResults = searchService.search(request, userId);

      if (searchResults.isEmpty()) {
        return ChatAnswerDTO.builder()
            .answer(List.of(AnswerSegmentDTO.builder()
                .text("I don't know the answer to your question.")
                .build()))
            .sources(List.of())
            .build();
      }

      String context = contextAssembler(searchResults);

      String prompt = String.format(
          PROMPT,
          context, request.getQuery());

      List<AnswerSegmentDTO> response = answerGenerator.generateAnswer(prompt);

      return ChatAnswerDTO.builder()
          .answer(sanitizeCites(response, searchResults.size()))
          .sources(searchResults)
          .build();
    } catch (Exception e) {
      log.error("Error generating answer for question: {}", question.getQuestion(), e);
      throw new RuntimeException("Error generating answer for question: " + question.getQuestion(), e);
    }
  }

  public void streamAnswer(ChatQuestionDTO question, String userId, SseEmitter emitter) {
    try {
      SearchRequestDTO request = SearchRequestDTO.builder()
          .query(question.getQuestion())
          .fileId(question.getFileId())
          .build();

      List<SearchResultDTO> searchResults = searchService.search(request, userId);

      if (searchResults.isEmpty()) {
        sendAnswer(emitter, "segment",
            AnswerSegmentDTO.builder().text("I don't know the answer to your question").cites(List.of()).build());
        return;
      }

      sendAnswer(emitter, "sources", searchResults);

      String context = contextAssembler(searchResults);

      String prompt = String.format(
          PROMPT,
          context, request.getQuery());

      answerGenerator.generateAnswerStream(
          prompt,
          segment -> {
            AnswerSegmentDTO sanitizedSegment = sanitizeCites(segment, searchResults.size());
            sendAnswer(
                emitter,
                "segment",
                sanitizedSegment);
          });
    } catch (Exception e) {
      log.error("Error generating answer for question: {}", question.getQuestion(), e);
      throw new RuntimeException(
          "Error generating answer for question: " + question.getQuestion() + "with error: " + e);
    }
  }
}
