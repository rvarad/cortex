package com.cortex.cortex_rag_orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;
import com.cortex.cortex_common.dto.ChatAnswerDTO;
import com.cortex.cortex_common.dto.ChatQuestionDTO;
import com.cortex.cortex_common.dto.SearchResultDTO;
import com.cortex.cortex_common.dto.SourceRefDTO;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    ChatQuestionDTO question = ChatQuestionDTO.builder().question("how do I set up docker?").build();

    // Program the fake retriever: for any request, hand back our one canned chunk.
    when(searchService.search(any(), any())).thenReturn(List.of(chunk));

    // Program the fake LLM to return a canned structured answer (one cited
    // segment).
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

    // Now prove ChatService built the prompt correctly by CAPTURING what it
    // actually
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

    ChatQuestionDTO question = ChatQuestionDTO.builder().question("how do I set up docker?").build();

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

  @Test
  void stream_emitsEachCitedSourceExactlyOnce_immediatelyBeforeTheSegmentThatCitesIt() {
    // ---- Arrange ----
    // Two sources => valid citation range is [1, 2]. The transcripts are here on
    // purpose: they must NEVER reach the wire (that's the whole point of SourceRefDTO).
    UUID fileId = UUID.randomUUID();
    SearchResultDTO source1 = SearchResultDTO.builder()
        .id(UUID.randomUUID()).fileId(fileId).fileDisplayName("docker-talk.mp4")
        .chunkIndex(0).startTime(0.0).endTime(60.0)
        .transcript("a long transcript that must not be shipped to the browser")
        .build();
    SearchResultDTO source2 = SearchResultDTO.builder()
        .id(UUID.randomUUID()).fileId(fileId).fileDisplayName("docker-talk.mp4")
        .chunkIndex(1).startTime(60.0).endTime(120.0)
        .transcript("another long transcript")
        .build();

    ChatQuestionDTO question = ChatQuestionDTO.builder().question("how do I set up docker?").build();
    when(searchService.search(any(), any())).thenReturn(List.of(source1, source2));

    // Drive the streaming callback ourselves with three canned segments:
    // seg1 cites 1 -> source 1 is new, must be emitted.
    // seg2 cites 2 and 9 -> 9 is out of range (scrubbed); source 2 is new.
    // seg3 re-cites 1 -> already sent, must NOT be emitted again.
    AnswerSegmentDTO seg1 = AnswerSegmentDTO.builder().text("Docker uses namespaces.").cites(List.of(1)).build();
    AnswerSegmentDTO seg2 = AnswerSegmentDTO.builder().text(" and cgroups.").cites(List.of(2, 9)).build();
    AnswerSegmentDTO seg3 = AnswerSegmentDTO.builder().text(" Both are kernel features.").cites(List.of(1)).build();
    doAnswer(invocation -> {
      Consumer<AnswerSegmentDTO> callback = invocation.getArgument(1);
      callback.accept(seg1);
      callback.accept(seg2);
      callback.accept(seg3);
      return null;
    }).when(answerGenerator).generateAnswerStream(anyString(), any());

    SseEmitter emitter = mock(SseEmitter.class);

    // ---- Act ----
    chatService.streamAnswer(question, "user-1", emitter);

    // ---- Assert ----
    // Exactly 5 events: source(1), segment, source(2), segment, segment.
    // Critically NOT an upfront "sources" blob — that's what lazy emission removed.
    ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    try {
      verify(emitter, times(5)).send(captor.capture());
    } catch (Exception e) {
      throw new RuntimeException(e); // send() declares IOException; a mock never throws it
    }
    List<Object> payloads = captor.getAllValues().stream().map(ChatServiceTest::payloadOf).toList();

    // Event 0: source 1 — emitted BEFORE the segment citing it (the one ordering rule).
    SourceRefDTO ref1 = (SourceRefDTO) payloads.get(0);
    assertThat(ref1.getSourceNo()).isEqualTo(1);
    assertThat(ref1.getFileId()).isEqualTo(fileId);
    assertThat(ref1.getFileDisplayName()).isEqualTo("docker-talk.mp4");
    assertThat(ref1.getStartTime()).isEqualTo(0.0);
    assertThat(ref1.getChunkIndex()).isEqualTo(0);

    // Event 1: the segment that cites it.
    assertThat(((AnswerSegmentDTO) payloads.get(1)).getCites()).containsExactly(1);

    // Event 2: source 2 — again, before its segment.
    SourceRefDTO ref2 = (SourceRefDTO) payloads.get(2);
    assertThat(ref2.getSourceNo()).isEqualTo(2);
    assertThat(ref2.getStartTime()).isEqualTo(60.0);

    // Event 3: the out-of-range 9 was scrubbed; only 2 survives.
    assertThat(((AnswerSegmentDTO) payloads.get(3)).getCites()).containsExactly(2);

    // Event 4: seg3 re-cites source 1 -> segment only, NO duplicate source event.
    assertThat(((AnswerSegmentDTO) payloads.get(4)).getCites()).containsExactly(1);

    // Only the 2 cited sources were ever emitted (dedup held).
    assertThat(payloads).filteredOn(p -> p instanceof SourceRefDTO).hasSize(2);
  }

  @Test
  void stream_emptyRetrieval_sendsDontKnow_andNeverOpensTheStream() {
    // ---- Arrange ----
    ChatQuestionDTO question = ChatQuestionDTO.builder().question("what is the meaning of life?").build();
    when(searchService.search(any(), any())).thenReturn(List.of());

    SseEmitter emitter = mock(SseEmitter.class);

    // ---- Act ----
    chatService.streamAnswer(question, "user-1", emitter);

    // ---- Assert ----
    // Exactly one event (the "don't know" segment); no "sources" event.
    ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    try {
      verify(emitter, times(1)).send(captor.capture());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    AnswerSegmentDTO fallback = (AnswerSegmentDTO) payloadOf(captor.getValue());
    assertThat(fallback.getText()).contains("don't know");

    // The core guarantee: empty context => the LLM stream is never opened.
    verify(answerGenerator, never()).generateAnswerStream(anyString(), any());
  }

  // Pulls the data object out of an SSE event, skipping the "event:...\n" framing
  // strings that .name() adds. Our payloads are always a List or an AnswerSegmentDTO.
  private static Object payloadOf(SseEmitter.SseEventBuilder builder) {
    for (ResponseBodyEmitter.DataWithMediaType d : builder.build()) {
      if (!(d.getData() instanceof String)) {
        return d.getData();
      }
    }
    return null;
  }
}
