package com.hesta.backend.repository;

import com.hesta.backend.entity.MaintenanceChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MaintenanceChatMessageRepository extends JpaRepository<MaintenanceChatMessage, UUID> {

    List<MaintenanceChatMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    void deleteByConversationId(UUID conversationId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "DELETE FROM MaintenanceChatMessage m WHERE m.conversation.id IN :conversationIds")
    int deleteByConversationIdIn(@org.springframework.data.repository.query.Param("conversationIds") java.util.Collection<UUID> conversationIds);
}
