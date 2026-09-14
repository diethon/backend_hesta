package com.hesta.backend.realtime.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RealtimeEvent<T>(
        String eventId,
        RealtimeEventType type,
        UUID homeId,
        UUID deviceId,
        T data,
        Instant timestamp
) {
    public RealtimeEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(homeId, "homeId must not be null");
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static <T> RealtimeEvent<T> create(
            RealtimeEventType type,
            UUID homeId,
            UUID deviceId,
            T data
    ) {
        return new RealtimeEvent<>(
                UUID.randomUUID().toString(),
                type,
                homeId,
                deviceId,
                data,
                Instant.now()
        );
    }

    public static <T> RealtimeEvent<T> create(RealtimeEventType type, UUID homeId, T data) {
        return create(type, homeId, null, data);
    }
}
