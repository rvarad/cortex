package com.cortex.cortex_rag_orchestration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cortex.cortex_common.utils.BackgroundTask;
import com.cortex.cortex_rag_orchestration.exception.ApiExceptionHandler;
import com.cortex.cortex_rag_orchestration.model.Conversation;
import com.cortex.cortex_rag_orchestration.repository.ConversationMessageRepository;
import com.cortex.cortex_rag_orchestration.repository.ConversationRepository;
import com.cortex.cortex_rag_orchestration.service.AgentEvent;
import com.cortex.cortex_rag_orchestration.service.AgentService;
import com.cortex.cortex_rag_orchestration.service.SearchService;
import com.cortex.cortex_rag_orchestration.service.StepEvent;
import com.cortex.cortex_rag_orchestration.service.conversation.ConversationHistory;
import com.cortex.cortex_rag_orchestration.service.conversation.ConversationService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * The REST controllers' boundary is ApiExceptionHandler (ADR-0014). Whatever goes wrong below it,
 * the caller gets the right status and an RFC 9457 ProblemDetail body with no exception text, and
 * the failure is logged once, there.
 *
 * <p>The streaming endpoint is the exception: a failure inside an answer belongs to the answer's own
 * boundary (SseEvents.answering), and must not be logged again here. This boundary only answers it,
 * with a 500 if the stream hasn't started yet.
 *
 * <p>Standalone MockMvc, not @WebMvcTest: the application class enables JPA repositories, so a
 * slice would try to build them with no database. ConversationService is the real one; only its
 * repositories and the agent are fakes. Security isn't under test: the user comes in as the
 * request's principal.
 */
class RagErrorResponsesTest {

  private static final String USER_ID = "user-1";

  /** Every logger of ours sits under "com.cortex", so this sees all of our lines. */
  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  /**
   * Thread-safe: the answer's thread logs while the test thread reads. Each event is frozen as it
   * arrives — logback reads MDC lazily, from whoever asks first.
   */
  private final List<ILoggingEvent> captured = new CopyOnWriteArrayList<>();

  private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      captured.add(event);
    }
  };

  private final ConversationRepository conversationRepository = mock(ConversationRepository.class);

  private final ConversationHistory history = mock(ConversationHistory.class);

  private final AgentService agentService = mock(AgentService.class);

  private final ConversationService conversationService = new ConversationService(
      conversationRepository, mock(ConversationMessageRepository.class), history, agentService, 50);

  private final SearchService searchService = mock(SearchService.class);

  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new ConversationController(conversationService), new SearchController(searchService))
      .setControllerAdvice(new ApiExceptionHandler())
      .build();

  @BeforeEach
  void attachAppender() {
    appender.start();
    cortexLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppenderAndClearMdc() {
    cortexLogger.detachAppender(appender);
    MDC.clear();
  }

  @Test
  void anotherUsersConversation_is404_loggedOnceAsAWarning_withTheConversationId() throws Exception {
    UUID conversationId = UUID.randomUUID();
    when(conversationRepository.findByIdAndUserId(conversationId, USER_ID)).thenReturn(Optional.empty());

    mockMvc.perform(asUser(get("/api/v1/conversations/{id}", conversationId)))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.detail").value("Conversation not found"));

    assertThat(linesAt(Level.ERROR)).as("ERROR lines: a missing conversation is not a server error").isEmpty();
    List<ILoggingEvent> warnings = linesAt(Level.WARN);
    assertThat(warnings).as("WARN lines").hasSize(1);
    assertThat(warnings.get(0).getLoggerName()).as("logged by the boundary")
        .isEqualTo(ApiExceptionHandler.class.getName());
    assertThat(warnings.get(0).getMDCPropertyMap()).containsEntry("conversationId", conversationId.toString());
  }

  @Test
  void askingInAnotherUsersConversation_is404_evenThoughTheCallerAsksForAStream() throws Exception {
    // The browser asks for text/event-stream. The 404 comes before any stream exists, so it must
    // still be a problem body, not an empty answer or a 406.
    UUID conversationId = UUID.randomUUID();
    when(conversationRepository.findByIdAndUserId(conversationId, USER_ID)).thenReturn(Optional.empty());

    mockMvc.perform(asUser(post("/api/v1/conversations/{id}/messages", conversationId))
        .accept(MediaType.TEXT_EVENT_STREAM)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"what did they decide?\"}"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("Conversation not found"));
  }

  @Test
  void anEmptyQuestion_is400_withTheReasonInTheDetail() throws Exception {
    mockMvc.perform(asUser(post("/api/v1/conversations/{id}/messages", UUID.randomUUID()))
        .accept(MediaType.TEXT_EVENT_STREAM)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("Question cannot be empty"));
  }

  @Test
  void anUnexpectedFailure_is500_withNoExceptionTextInTheBody_andOneErrorLine() throws Exception {
    when(searchService.search(any(), any())).thenThrow(new IllegalStateException("api key is AIza-secret"));

    String body = mockMvc.perform(asUser(post("/api/v1/search"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"query\":\"budget\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(500))
        .andReturn().getResponse().getContentAsString();

    assertThat(body).as("the body tells the caller nothing about our internals").doesNotContain("AIza-secret");

    List<ILoggingEvent> errors = linesAt(Level.ERROR);
    assertThat(errors).as("ERROR lines").hasSize(1);
    ILoggingEvent error = errors.get(0);
    assertThat(error.getLoggerName()).as("logged by the boundary").isEqualTo(ApiExceptionHandler.class.getName());
    assertThat(error.getThrowableProxy()).as("with the exception attached").isNotNull();
    assertThat(error.getThrowableProxy().getMessage()).isEqualTo("api key is AIza-secret");
  }

  @Test
  void anAnswerThatFailsAfterItsFirstEvent_isLoggedOnce_byTheStreamsBoundary() throws Exception {
    // Spring commits the stream on its first event: the 200 is out, nothing more can be sent.
    UUID conversationId = UUID.randomUUID();
    agentFails(conversationId, true);

    MockHttpServletResponse response = askAndRedispatchTheFailure(conversationId);

    assertThat(response.getStatus()).as("the stream's 200 stands").isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
    assertOneErrorLine_fromTheStreamsBoundary(conversationId);
  }

  @Test
  void anAnswerThatFailsBeforeAnyEvent_is500_andLoggedOnce_byTheStreamsBoundary() throws Exception {
    // No event yet, so nothing is committed: the caller can still get a real error status.
    UUID conversationId = UUID.randomUUID();
    agentFails(conversationId, false);

    MockHttpServletResponse response = askAndRedispatchTheFailure(conversationId);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(response.getContentAsString()).doesNotContain("model unavailable");
    assertOneErrorLine_fromTheStreamsBoundary(conversationId);
  }

  /** The conversation is ours; the agent fails, after one step event or before any. */
  private void agentFails(UUID conversationId, boolean afterOneEvent) {
    when(conversationRepository.findByIdAndUserId(conversationId, USER_ID))
        .thenReturn(Optional.of(Conversation.builder().id(conversationId).userId(USER_ID).title("New chat").build()));
    when(history.replay(conversationId, USER_ID)).thenReturn(new ConversationHistory.Replay(List.of(), false));
    when(agentService.answerStream(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
      if (afterOneEvent) {
        Consumer<AgentEvent> onEvent = invocation.getArgument(4);
        onEvent.accept(new StepEvent("Searching library…"));
      }
      throw new IllegalStateException("model unavailable");
    });
  }

  /**
   * The controller hands back the emitter; the answer fails on its own thread. completeWithError
   * hands that failure back to Spring, which runs it through the exception handlers on a second,
   * async dispatch — the one a real container makes too.
   */
  private MockHttpServletResponse askAndRedispatchTheFailure(UUID conversationId) throws Exception {
    MvcResult streaming = mockMvc.perform(asUser(post("/api/v1/conversations/{id}/messages", conversationId))
        .accept(MediaType.TEXT_EVENT_STREAM)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"what did they decide?\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();

    streaming.getAsyncResult(Duration.ofSeconds(5).toMillis());
    return mockMvc.perform(asyncDispatch(streaming)).andReturn().getResponse();
  }

  private void assertOneErrorLine_fromTheStreamsBoundary(UUID conversationId) throws InterruptedException {
    List<ILoggingEvent> errors = awaitErrors(Duration.ofSeconds(5));

    assertThat(errors).as("ERROR lines").hasSize(1);
    ILoggingEvent error = errors.get(0);
    assertThat(error.getLoggerName()).as("logged by the stream's boundary").isEqualTo(BackgroundTask.class.getName());
    assertThat(error.getThrowableProxy().getMessage()).isEqualTo("model unavailable");
    assertThat(error.getMDCPropertyMap()).containsEntry("conversationId", conversationId.toString());
  }

  /**
   * The browser's own Accept header, and the user the gateway's token would carry. The Accept
   * header matters here: rag has an XML converter, and standalone MockMvc, unlike Spring Boot, puts
   * it before the JSON one. The streaming tests replace it with text/event-stream.
   */
  private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
    return request
        .accept(MediaType.APPLICATION_JSON)
        .principal(new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
  }

  private List<ILoggingEvent> linesAt(Level level) {
    return captured.stream().filter(logged -> logged.getLevel() == level).toList();
  }

  /**
   * The answer's thread logs on its own time. Poll until the first ERROR line arrives, then wait a
   * little longer, so a second line for the same failure would be counted too.
   */
  private List<ILoggingEvent> awaitErrors(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();

    while (System.nanoTime() < deadline) {
      if (!linesAt(Level.ERROR).isEmpty()) {
        Thread.sleep(200);
        return linesAt(Level.ERROR);
      }
      Thread.sleep(20);
    }

    return linesAt(Level.ERROR);
  }
}
