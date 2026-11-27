package com.cortex.cortex_rag_orchestration.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cortex.cortex_rag_orchestration.model.ConversationMessage;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

  /** Whether the conversation has any messages yet — the first one sets the title. */
  boolean existsByConversationId(UUID conversationId);

  /**
   * The newest rows of a conversation, newest first, and only as many as asked for. Every reader
   * — reload, the replay window, the recall tool — goes through this with its own limit, so no
   * caller ever loads a whole conversation. Served from the (conversation_id, created_at) index.
   *
   * <p>Ownership is checked on the conversation before this is called; messages inherit it.
   */
  @Query(value = """
      SELECT * FROM conversation_message
      WHERE conversation_id = :conversationId
      ORDER BY created_at DESC
      LIMIT :limit OFFSET :offset""", nativeQuery = true)
  List<ConversationMessage> findLatest(@Param("conversationId") UUID conversationId,
      @Param("limit") int limit, @Param("offset") int offset);
}
