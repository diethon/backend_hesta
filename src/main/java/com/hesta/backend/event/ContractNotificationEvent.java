package com.hesta.backend.event;

import com.hesta.backend.enums.NotificationPriority;

import java.util.Set;
import java.util.UUID;

/** Published inside contract transactions; delivered after commit via NotificationService + WebSocket. */
public record ContractNotificationEvent(
        UUID homeId,
        Set<UUID> recipientIds,
        String title,
        String message,
        NotificationPriority priority
) {}
