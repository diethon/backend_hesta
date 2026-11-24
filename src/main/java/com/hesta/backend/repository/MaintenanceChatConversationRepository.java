package com.hesta.backend.repository;

import com.hesta.backend.entity.MaintenanceChatConversation;
import com.hesta.backend.enums.MaintenanceChatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaintenanceChatConversationRepository extends JpaRepository<MaintenanceChatConversation, UUID> {

    Optional<MaintenanceChatConversation> findByIdAndHomeIdAndUserId(UUID id, UUID homeId, UUID userId);

    long countByUserIdAndStatus(UUID userId, MaintenanceChatStatus status);

    List<MaintenanceChatConversation> findByHomeIdAndUserIdAndStatusOrderByUpdatedAtDesc(
            UUID homeId, UUID userId, MaintenanceChatStatus status);

    @org.springframework.data.jpa.repository.Query(
            "SELECT c.id FROM MaintenanceChatConversation c WHERE c.status <> :activeStatus AND c.updatedAt < :cutoff")
    List<UUID> findFinishedIdsUpdatedBefore(
            @org.springframework.data.repository.query.Param("activeStatus") MaintenanceChatStatus activeStatus,
            @org.springframework.data.repository.query.Param("cutoff") java.time.OffsetDateTime cutoff);
}
