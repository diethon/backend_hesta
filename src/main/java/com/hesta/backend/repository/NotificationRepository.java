package com.hesta.backend.repository;

import com.hesta.backend.entity.Notification;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
            SELECT n FROM Notification n
            LEFT JOIN HomeMember hm ON hm.home = n.home AND hm.user.id = :userId
            WHERE n.recipient.id = :userId
              AND (:homeId IS NULL OR n.home.id = :homeId)
              AND (:isRead IS NULL OR n.read = :isRead)
              AND (:type IS NULL OR n.type = :type)
              AND (:priority IS NULL OR n.priority = :priority)
              AND (
                   hm IS NULL
                   OR hm.role = com.hesta.backend.enums.HomeRole.OWNER
                   OR hm.accessScope = com.hesta.backend.enums.AccessScope.ALL
                   OR n.type IN (com.hesta.backend.enums.NotificationType.SECURITY, com.hesta.backend.enums.NotificationType.SYSTEM)
                   OR n.priority = com.hesta.backend.enums.NotificationPriority.HIGH
              )
            """)
    Page<Notification> findAccessible(
            @Param("userId") UUID userId,
            @Param("homeId") UUID homeId,
            @Param("isRead") Boolean isRead,
            @Param("type") NotificationType type,
            @Param("priority") NotificationPriority priority,
            Pageable pageable
    );

    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Notification n
            SET n.read = true
            WHERE n.recipient.id = :userId
              AND n.read = false
              AND (:homeId IS NULL OR n.home.id = :homeId)
            """)
    int markAllAsRead(@Param("userId") UUID userId, @Param("homeId") UUID homeId);
}
