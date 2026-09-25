package com.hesta.backend.repository;

import com.hesta.backend.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipient.id = :recipientId AND n.read = false")
    int markAllReadForUser(@Param("recipientId") UUID recipientId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipient.id = :recipientId "
            + "AND n.home.id = :homeId AND n.read = false")
    int markAllReadForHome(@Param("recipientId") UUID recipientId, @Param("homeId") UUID homeId);
}
