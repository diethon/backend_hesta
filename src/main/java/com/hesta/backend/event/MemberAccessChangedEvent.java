package com.hesta.backend.event;

import java.util.UUID;

/**
 * Published after member access permissions are committed.
 * Phase 2c will consume this event for realtime WebSocket disconnect / topic cache invalidation.
 */
public record MemberAccessChangedEvent(UUID homeId, UUID userId) {
}
