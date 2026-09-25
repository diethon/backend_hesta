package com.hesta.backend.dto.command;

import com.hesta.backend.dto.response.NotificationRealtimePayload;

public record NotificationCreatedEvent(NotificationRealtimePayload payload) {
}
