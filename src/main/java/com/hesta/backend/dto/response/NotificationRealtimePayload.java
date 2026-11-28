package com.hesta.backend.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Safe home-topic payload. Notification content is retrieved through the
 * recipient-authorized REST API and is not broadcast to every home member.
 */
public record NotificationRealtimePayload(
        UUID notificationId,
        UUID recipientId,
        UUID homeId,
        boolean isRead,
        OffsetDateTime createdAt,
        boolean silent
) {
    /** {@code silent}: recipient is in quiet hours, so clients list it without popup or sound. */
    public static NotificationRealtimePayload from(NotificationResponse notification, boolean silent) {
        return new NotificationRealtimePayload(
                notification.id(),
                notification.userId(),
                notification.homeId(),
                notification.isRead(),
                notification.createdAt(),
                silent
        );
    }
}
