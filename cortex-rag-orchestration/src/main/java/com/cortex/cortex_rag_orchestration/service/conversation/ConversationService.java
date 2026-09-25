package com.cortex.cortex_rag_orchestration.service.conversation;

import com.cortex.cortex_common.dto.AnswerSegmentDTO;
import com.cortex.cortex_common.dto.AttachedFileDTO;
import com.cortex.cortex_common.dto.ConversationDTO;
import com.cortex.cortex_common.dto.ConversationMessageDTO;
import com.cortex.cortex_common.dto.ConversationSummaryDTO;
import com.cortex.cortex_common.dto.SourceRefDTO;
import com.cortex.cortex_rag_orchestration.model.Conversation;
import com.cortex.cortex_rag_orchestration.model.ConversationMessage;
import com.cortex.cortex_rag_orchestration.model.MessageRole;
import com.cortex.cortex_rag_orchestration.repository.ConversationMessageRepository;
import com.cortex.cortex_rag_orchestration.repository.ConversationRepository;
import com.cortex.cortex_rag_orchestration.service.AgentEvent;
import com.cortex.cortex_rag_orchestration.service.AgentService;
import com.cortex.cortex_rag_orchestration.service.SegmentEvent;
import com.cortex.cortex_rag_orchestration.service.SourceEvent;
import com.cortex.cortex_rag_orchestration.service.StepEvent;
import com.cortex.cortex_rag_orchestration.service.ToolCallEvent;
import com.cortex.cortex_rag_orchestration.service.conversation.ConversationHistory.Replay;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The conversation layer: sits between the controller and the agent (plan §5.5).
 *
 * <p>It owns everything the agent deliberately does not — loading and saving turns, building the
 * replay window, checking who owns what. The agent stays a function of "messages in, events out".
 *
 * <p>Every lookup is by id <em>and</em> user. Another user's conversation is not forbidden, it does
 * not exist: 404, same as a made-up id.
 */
@Slf4j
@Service
public class ConversationService {

  /** Before the first message lands and gives the conversation its real title. */
  static final String UNTITLED = "New chat";

  private static final int TITLE_LENGTH = 80;

  private final ConversationRepository conversationRepository;

  private final ConversationMessageRepository messageRepository;

  private final ConversationHistory history;

  private final AgentService agentService;

  /**
   * Rows a reload returns — the newest ones. A "load earlier" cursor is a frontend concern
   * (Brick 10); the backend never hands back a whole conversation.
   */
  private final int reloadRows;

  public ConversationService(ConversationRepository conversationRepository,
      ConversationMessageRepository messageRepository, ConversationHistory history,
      AgentService agentService, @Value("${chat.conversation.reload-rows}") int reloadRows) {
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
    this.history = history;
    this.agentService = agentService;
    this.reloadRows = reloadRows;
  }

  public ConversationSummaryDTO create(String userId) {
    Conversation conversation = conversationRepository.save(
        Conversation.builder().userId(userId).title(UNTITLED).build());

    log.info("[conversation] created {} for user {}", conversation.getId(), userId);

    return summary(conversation);
  }

  public List<ConversationSummaryDTO> list(String userId) {
    return conversationRepository.findAllByUserIdOrderByUpdatedAtDesc(userId).stream()
        .map(this::summary)
        .toList();
  }

  public ConversationDTO get(UUID id, String userId) {
    Conversation conversation = requireOwned(id, userId);

    List<ConversationMessage> rows = ConversationHistory.oldestFirst(
        messageRepository.findLatest(id, reloadRows, 0));

    Map<UUID, String> fileNames = history.fileNames(rows, userId);

    List<ConversationMessageDTO> messages = new ArrayList<>();

    for (ConversationMessage row : rows) {
      messages.add(switch (row.getRole()) {
        case USER -> {
          List<AttachedFileDTO> attachments = row.getAttachedFileIds().stream()
              .map(fileId -> new AttachedFileDTO(fileId, fileNames.get(fileId)))
              .toList();
          yield new ConversationMessageDTO(row.getId(), row.getRole().name(), row.getCreatedAt(),
              history.userText(row), attachments, null, null);
        }
        case ASSISTANT -> {
          AssistantContent answer = history.assistantContent(row);
          yield new ConversationMessageDTO(row.getId(), row.getRole().name(), row.getCreatedAt(),
              null, null, answer.segments(), answer.sources());
        }
      });
    }

    return new ConversationDTO(conversation.getId(), conversation.getTitle(), messages);
  }

  public void delete(UUID id, String userId) {
    Conversation conversation = requireOwned(id, userId);

    // The FK cascades the messages.
    conversationRepository.delete(conversation);

    log.info("[conversation] deleted {} for user {}", id, userId);
  }

  /**
   * The owner check, exposed so the streaming endpoint can 404 <em>before</em> it hands back an
   * emitter — otherwise the client gets a 200 stream that immediately errors.
   */
  public Conversation requireOwned(UUID id, String userId) {
    return conversationRepository.findByIdAndUserId(id, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
  }

  /**
   * The write path (§5.5): save the question, replay the prior turns, run the agent, save the
   * answer.
   *
   * <p>Not transactional on purpose. This runs for as long as the agent does — a minute is normal —
   * and holding a connection open that long is wrong. Each save is its own short transaction.
   *
   * <p>If the agent does not finish, or finishes with nothing to say, nothing is saved for the
   * answer: not on a crash, not when the client goes away (the failed {@code send} throws inside
   * {@code onEvent}, which is what unwinds the agent), and not when the loop hits its iteration cap
   * — that one returns normally, so it has to be checked for. The question row stays. Half answers
   * are not kept.
   *
   * <p>An empty answer is not just untidy: replayed, it becomes a model turn with no parts, Gemini
   * rejects the whole request, and every later question in the conversation fails the same way.
   */
  public void ask(UUID id, String userId, String question, List<UUID> fileIds,
      Consumer<AgentEvent> onEvent) {

    Conversation conversation = requireOwned(id, userId);

    // Built before the question is inserted: the window ends on the last answer, and the recall
    // tool's offset assumes exactly that.
    Replay replay = history.replay(id, userId);
    List<Message> messages = new ArrayList<>(replay.messages());
    messages.add(new UserMessage(question));

    boolean first = !messageRepository.existsByConversationId(id);

    saveUser(conversation, question, fileIds, first);

    log.info("[conversation] {}: {} message(s) replayed, older turns {}",
        id, replay.messages().size(), replay.hasOlder() ? "exist" : "none");

    List<AnswerSegmentDTO> segments = new ArrayList<>();
    List<SourceRefDTO> sources = new ArrayList<>();
    List<RecordedToolCall> toolCalls = new ArrayList<>();

    boolean finished = agentService.answerStream(messages, userId, fileIds, id, event -> {
      onEvent.accept(event);
      switch (event) {
        case SegmentEvent segment -> segments.add(segment.segment());
        case SourceEvent source -> sources.add(source.source());
        case ToolCallEvent call -> toolCalls.add(
            new RecordedToolCall(call.round(), call.name(), history.arguments(call.arguments())));
        case StepEvent ignored -> {
        }
      }
    });

    if (!finished) {
      log.info("[conversation] {}: answer not saved — the agent hit its iteration cap", id);
      return;
    }

    if (segments.isEmpty()) {
      log.warn("[conversation] {}: answer not saved — the agent finished with no segments", id);
      return;
    }

    saveAssistant(conversation, new AssistantContent(segments, sources), toolCalls);
  }

  private void saveUser(Conversation conversation, String question, List<UUID> fileIds,
      boolean first) {

    messageRepository.save(ConversationMessage.builder()
        .conversationId(conversation.getId())
        .role(MessageRole.USER)
        .content(history.write(new UserContent(question)))
        .attachedFileIds(fileIds == null ? List.of() : List.copyOf(fileIds))
        .build());

    if (first) {
      conversation.setTitle(title(question));
    }

    touch(conversation);
  }

  /** What the model said goes in {@code content}; what it did goes in {@code toolCalls}. */
  private void saveAssistant(Conversation conversation, AssistantContent answer,
      List<RecordedToolCall> toolCalls) {
    messageRepository.save(ConversationMessage.builder()
        .conversationId(conversation.getId())
        .role(MessageRole.ASSISTANT)
        .content(history.write(answer))
        .toolCalls(history.write(toolCalls))
        .build());

    touch(conversation);
  }

  private void touch(Conversation conversation) {
    conversation.setUpdatedAt(LocalDateTime.now());
    conversationRepository.save(conversation);
  }

  /** The first question, trimmed. Enough to recognise a chat in the sidebar. */
  static String title(String question) {
    String trimmed = question.strip().replaceAll("\\s+", " ");

    return trimmed.length() <= TITLE_LENGTH
        ? trimmed
        : trimmed.substring(0, TITLE_LENGTH - 1).stripTrailing() + "…";
  }

  private ConversationSummaryDTO summary(Conversation conversation) {
    return new ConversationSummaryDTO(conversation.getId(), conversation.getTitle(),
        conversation.getCreatedAt(), conversation.getUpdatedAt());
  }
}
