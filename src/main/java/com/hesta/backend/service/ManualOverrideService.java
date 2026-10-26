package com.hesta.backend.service;

import com.hesta.backend.entity.Device;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface ManualOverrideService {
    OffsetDateTime activate(Device device, UUID userId, String reason);
    boolean isActive(UUID deviceId);
    OffsetDateTime getOverrideUntil(UUID deviceId);
    java.util.Map<UUID, OffsetDateTime> getOverridesUntil(java.util.Collection<UUID> deviceIds);
    void resumeAutomation(Device device, UUID userId);
    OffsetDateTime recordOverride(UUID deviceId, UUID userId, int minutes);
    OffsetDateTime extendOverride(UUID deviceId, UUID userId, int minutes);
}
