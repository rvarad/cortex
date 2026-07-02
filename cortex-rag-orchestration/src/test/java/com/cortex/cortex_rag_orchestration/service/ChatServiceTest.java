package com.cortex.cortex_rag_orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;
import com.cortex.cortex_common.dto.ChatAnswerDTO;
import com.cortex.cortex_common.dto.ChatQuestionDTO;
import com.cortex.cortex_common.dto.SearchResultDTO;

/**
 * Pure unit test for ChatService. No Spring, no database, no embeddings, no
 * real LLM.
 *
 * The two collaborators (SearchService, AnswerGenerator) are replaced with
 * Mockito
 * fakes that we program by hand, so we test ONLY ChatService's own logic:
 * - does it assemble retrieved chunks into the prompt context?
 * - does it wrap them in the grounding instruction?
 * - does it pass the LLM's segments and the sources through to the response?
 * - does it short-circuit (and never call the LLM) when retrieval is empty?
 */
class ChatServiceTest {

  // mock(X.class) builds a fake X. Every method returns null/empty until we say
  // otherwise.
  private final SearchService searchService = mock(SearchService.class);
  private final AnswerGenerator answerGenerator = mock(AnswerGenerator.class);

  // The real object under test, fed the two fakes via its constructor.
  private final ChatService chatService = new ChatService(searchService, answerGenerator);

  @Test
  void happyPath_assemblesContextAndReturnsGroundedAnswerWithSources() {
    // ---- Arrange ----
    SearchResultDTO chunk = SearchResultDTO.builder()
        .id(UUID.randomUUID())
        .fileId(UUID.randomUUID())
        .fileDisplayName("demo.mp4")
        .chunkIndex(3)
        .startTime(180.0)
        .endTime(300.0)
        .transcript("docker setup guide")
        .visualSummary("a terminal showing docker commands")
        .languageCode("en")
        .score(0.95)
        .build();

    ChatQuestionDTO question = ChatQuestionDTO.builder()
        .question("how do I set up docker?")
        .build();

    // Program the fake retriever: for any request, hand back our one canned chunk.
    when(searchService.search(any(), any())).thenReturn(List.of(chunk));

    // Program the fake LLM to return a canned structured answer (one cited segment).
    // The generator's own JSON/schema behaviour is tested elsewhere; here we only
    // care that ChatService passes these segments straight through.
    AnswerSegmentDTO segment = AnswerSegmentDTO.builder()
        .text("Run docker compose up.")
        .cites(List.of(1))
        .build();
    when(answerGenerator.generateAnswer(anyString())).thenReturn(List.of(segment));

    // ---- Act ----
    ChatAnswerDTO result = chatService.generateAnswer(question, "user-1");

    // ---- Assert ----
    // The LLM's segments pass straight through to the response (citation indices
    // point into the sources list below).
    assertThat(result.getAnswer()).containsExactly(segment);
    // Sources pass straight through too (seeds G.2 citations).
    assertThat(result.getSources()).containsExactly(chunk);

    // Now prove ChatService built the prompt correctly by CAPTURING what it actually
    // sent the LLM — the generator is a fake, so the only place this logic is
    // observable is the argument it received.
    ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
    verify(answerGenerator).generateAnswer(promptCaptor.capture());
    String prompt = promptCaptor.getValue();

    // The chunk's transcript made it into the context block...
    assertThat(prompt).contains("docker setup guide");
    // ...and the visual summary too (proves both fields are assembled).
    assertThat(prompt).contains("a terminal showing docker commands");
    // ...wrapped in the grounding instruction (proves it's RAG, not a bare
    // passthrough).
    assertThat(prompt).contains("using ONLY the information");
    // ...and the original question is in the prompt.
    assertThat(prompt).contains("how do I set up docker?");
  }

  @Test
  void emptyRetrieval_returnsDontKnow_andNeverCallsTheLlm() {
    // ---- Arrange ----
    ChatQuestionDTO question = ChatQuestionDTO.builder()
        .question("what is the meaning of life?")
        .build();

    // Retrieval finds nothing. We deliberately do NOT program answerGenerator here:
    // it must never be called, and stubbing an unused method would fail strict
    // mocking.
    when(searchService.search(any(), any())).thenReturn(List.of());

    // ---- Act ----
    ChatAnswerDTO result = chatService.generateAnswer(question, "user-1");

    // ---- Assert ----
    // The "don't know" fallback is a single segment with no citations.
    assertThat(result.getAnswer()).hasSize(1);
    assertThat(result.getAnswer().get(0).getText()).contains("don't know");
    assertThat(result.getSources()).isEmpty();
    // The core guarantee: empty context => the LLM is never invoked (no
    // hallucination risk).
    verifyNoInteractions(answerGenerator);
  }

  @Test
  void outOfRangeCites_areDropped_whileTextAndValidCitesSurvive() {
    // ---- Arrange ----
    // Two real sources => the only valid "Source no." values are 1 and 2.
    // Anything else (0, negatives, or > sourceCount) is an index the LLM
    // invented and must be scrubbed before it reaches the frontend.
    SearchResultDTO source1 = SearchResultDTO.builder()
        .id(UUID.randomUUID())
        .fileDisplayName("a.mp4")
        .build();
    SearchResultDTO source2 = SearchResultDTO.builder()
        .id(UUID.randomUUID())
        .fileDisplayName("b.mp4")
        .build();

    ChatQuestionDTO question = ChatQuestionDTO.builder()
        .question("how do I set up docker?")
        .build();

    when(searchService.search(any(), any())).thenReturn(List.of(source1, source2));

    // The fake LLM cites 0 (below range), 1 and 2 (valid), a duplicate 2, and
    // 5 (above range) — so this exercises range-filtering AND de-duplication.
    AnswerSegmentDTO segment = AnswerSegmentDTO.builder()
        .text("Grounded claim about docker.")
        .cites(List.of(0, 1, 2, 2, 5))
        .build();
    when(answerGenerator.generateAnswer(anyString())).thenReturn(List.of(segment));

    // ---- Act ----
    ChatAnswerDTO result = chatService.generateAnswer(question, "user-1");

    // ---- Assert ----
    // Only the in-range cites survive, in order; 0 and 5 are gone.
    assertThat(result.getAnswer()).hasSize(1);
    assertThat(result.getAnswer().get(0).getCites()).containsExactly(1, 2);
    // We drop bad citations, never the segment's content.
    assertThat(result.getAnswer().get(0).getText()).isEqualTo("Grounded claim about docker.");
  }
}
