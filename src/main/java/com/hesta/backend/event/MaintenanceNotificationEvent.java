package com.hesta.backend.event;

import com.hesta.backend.enums.NotificationPriority;

import java.util.Set;
import java.util.UUID;

/** Published inside the ticket transaction; delivered only after it commits. */
public record MaintenanceNotificationEvent(
        UUID homeId,
        Set<UUID> recipientIds,
        String title,
        String message,
        NotificationPriority priority
) {
}
