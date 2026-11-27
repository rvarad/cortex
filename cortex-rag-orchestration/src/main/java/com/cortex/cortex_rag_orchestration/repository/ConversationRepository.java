package com.cortex.cortex_rag_orchestration.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cortex.cortex_rag_orchestration.model.Conversation;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

  /** Every lookup is by owner as well as id: another user's conversation does not exist. */
  Optional<Conversation> findByIdAndUserId(UUID id, String userId);

  /** The sidebar. Served from the (user_id, updated_at DESC) index. */
  List<Conversation> findAllByUserIdOrderByUpdatedAtDesc(String userId);
}
