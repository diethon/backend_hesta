package com.hesta.backend.dto.response;

import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.SecurityEvent;
import com.hesta.backend.enums.SecurityEventStatus;
import com.hesta.backend.enums.SecurityEventType;
import com.hesta.backend.enums.SeverityLevel;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SecurityEventResponse(
        UUID id,
        UUID homeId,
        UUID deviceId,
        String deviceName,
        String roomName,
        SecurityEventType eventType,
        String location,
        SeverityLevel severity,
        SecurityEventStatus status,
        OffsetDateTime detectedAt
) {
    public static SecurityEventResponse from(SecurityEvent event) {
        Device device = event.getDevice();
        return new SecurityEventResponse(
                event.getId(),
                event.getHome().getId(),
                device != null ? device.getId() : null,
                device != null ? device.getName() : null,
                device != null && device.getRoom() != null ? device.getRoom().getName() : null,
                event.getEventType(),
                event.getLocation(),
                event.getSeverity(),
                event.getStatus(),
                event.getDetectedAt()
        );
    }
}
