package com.hesta.backend.service;

import com.hesta.backend.entity.Device;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface ManualOverrideService {
    OffsetDateTime activate(Device device, UUID userId, String reason);
    boolean isActive(UUID deviceId);
}
