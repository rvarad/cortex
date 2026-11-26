package com.cortex.cortex_rag_orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

import com.cortex.cortex_rag_orchestration.service.conversation.ConversationHistory;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.publisher.Flux;

/**
 * Segments are sent to the client from inside the parser's callback. When the client has left,
 * that send throws, and the agent must stop at once: stop reading the model's stream and cancel
 * it, so no more tokens are paid for. It must not take the failed send for bad JSON.
 *
 * <p>The chat model is a fake whose stream never ends by itself, like a model still writing: an
 * agent that kept reading would wait on it forever.
 */
class AgentServiceStreamTest {

  /** Stands for the send failing because the client left; the real type is the controller's. */
  private static final class ClientLeft extends RuntimeException {
    ClientLeft() {
      super("client left");
    }
  }

  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

  private final ChatModel chatModel = mock(ChatModel.class);

  private final AttachmentLoader attachmentLoader = mock(AttachmentLoader.class);

  private final AgentService agentService = new AgentService(chatModel, mock(ToolCallingManager.class),
      new LibraryTools(mock(SearchService.class), mock(SourceFormatter.class), mock(ConversationHistory.class)),
      attachmentLoader, new ObjectMapper());

  private final AtomicBoolean cancelled = new AtomicBoolean();

  @BeforeEach
  void setUp() {
    when(attachmentLoader.load(any(), any(), any(), any())).thenReturn(new LoadedAttachments(List.of(), "", List.of()));
    Flux<ChatResponse> stillWriting = Flux.just(
        "[{\"text\":\"Docker uses namespaces\",\"cites\":[]}",
        ",{\"text\":\" and cgroups\",\"cites\":[]}")
        .map(piece -> new ChatResponse(List.of(new Generation(new AssistantMessage(piece)))))
        .concatWith(Flux.never())
        .doOnCancel(() -> cancelled.set(true));
    when(chatModel.stream(any(Prompt.class))).thenReturn(stillWriting);

    captured.start();
    cortexLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    cortexLogger.detachAppender(captured);
  }

  @Test
  void aClientThatLeaves_stopsTheAgentAtOnce_andCancelsTheModelsStream() {
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(() -> agentService.answerStream(
        List.of(new UserMessage("What does Docker use?")), "user-1", List.of(), UUID.randomUUID(), event -> {
          if (event instanceof SegmentEvent) {
            throw new ClientLeft();
          }
        }))
        .as("the failed send stops the agent")
        .isInstanceOf(ClientLeft.class));

    assertThat(cancelled).as("the model's stream is cancelled, not left running").isTrue();
    assertThat(captured.list).as("WARN lines")
        .noneMatch(line -> line.getLevel() == Level.WARN && line.getLoggerName().equals(AgentService.class.getName()));
  }
}
