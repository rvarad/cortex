package com.cortex.cortex_rag_orchestration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.cortex.cortex_common.dto.ChatQuestionDTO;
import com.cortex.cortex_common.utils.BackgroundTask;
import com.cortex.cortex_rag_orchestration.service.conversation.ConversationService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Each answer in a conversation runs on a thread nobody waits for: the controller returns the SSE
 * emitter at once and the agent writes to it later (ADR-0014). So the top of that thread is a
 * boundary, and a failure there has to end in one ERROR line from it, carrying the conversationId.
 *
 * <p>Drives the real controller with ConversationService faked to throw, then waits for the
 * answer's thread to fail.
 */
class ConversationAnswerBoundaryTest {

  /** Every logger of ours sits under "com.cortex", so this sees all of our ERROR lines. */
  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  /**
   * Thread-safe, because events arrive on the answer's thread while the test thread reads them.
   * Each event is frozen as it arrives — logback reads MDC lazily, from whoever asks first.
   */
  private final List<ILoggingEvent> captured = new CopyOnWriteArrayList<>();

  private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
    @Override
    protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      captured.add(event);
    }
  };

  private final ConversationService conversationService = mock(ConversationService.class);

  private final ConversationController controller = new ConversationController(conversationService);

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
  void aFailingAnswer_isLoggedOnceAtItsBoundary_withTheConversationId() throws InterruptedException {
    // ---- Arrange: the agent fails as soon as the answer starts ----
    UUID conversationId = UUID.randomUUID();
    doThrow(new IllegalStateException("model unavailable"))
        .when(conversationService).ask(any(), any(), any(), any(), any());

    ChatQuestionDTO question = ChatQuestionDTO.builder().question("what did they decide?").build();

    // ---- Act: the controller hands back the emitter at once; the answer fails on its own thread
    controller.ask(conversationId, question, new UsernamePasswordAuthenticationToken("user-1", null, List.of()));

    List<ILoggingEvent> errors = awaitErrors(Duration.ofSeconds(5));

    // ---- Assert ----
    assertThat(errors).as("ERROR lines from our code").hasSize(1);

    ILoggingEvent error = errors.get(0);
    assertThat(error.getLoggerName()).as("logged by the boundary").isEqualTo(BackgroundTask.class.getName());
    assertThat(error.getThrowableProxy()).isNotNull();
    assertThat(error.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
    assertThat(error.getThrowableProxy().getMessage()).isEqualTo("model unavailable");
    assertThat(error.getMDCPropertyMap()).containsEntry("conversationId", conversationId.toString());
  }

  /**
   * Nothing to join: the answer's thread belongs to the controller. So poll until the first ERROR
   * line arrives, then wait a little longer, so a second line for the same failure would be counted
   * too.
   */
  private List<ILoggingEvent> awaitErrors(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();

    while (System.nanoTime() < deadline) {
      if (!errors().isEmpty()) {
        Thread.sleep(200);
        return errors();
      }
      Thread.sleep(20);
    }

    return errors();
  }

  private List<ILoggingEvent> errors() {
    return captured.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
  }
}
