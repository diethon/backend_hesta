package com.hesta.backend.dto.command;

import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;

import java.util.UUID;

/**
 * Internal contract used by backend feature modules to request a notification.
 */
public record NotificationEvent(
        UUID userId,
        UUID homeId,
        NotificationType type,
        String title,
        String message,
        NotificationPriority priority
) {
}
