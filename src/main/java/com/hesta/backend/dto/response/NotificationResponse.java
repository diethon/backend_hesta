package com.hesta.backend.dto.response;

import com.hesta.backend.entity.Notification;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID userId,
        UUID homeId,
        NotificationType type,
        String title,
        String message,
        NotificationPriority priority,
        boolean isRead,
        OffsetDateTime createdAt
) {
    public static NotificationResponse fromEntity(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getRecipient().getId(),
                notification.getHome() == null ? null : notification.getHome().getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getPriority(),
                notification.isRead(),
                notification.getCreatedAt()
        );
    }
}
