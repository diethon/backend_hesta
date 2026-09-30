package com.hesta.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.TwinHealthStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Also the complete single-node data payload for DEVICE_STATE_CHANGED. */
public record TwinDeviceSnapshotResponse(
        UUID deviceId, UUID roomId, String name, String deviceType, String icon,
        DeviceStatus status, JsonNode currentState, OffsetDateTime lastSeen, TwinHealthStatus healthStatus
) {
    public TwinDeviceSnapshotResponse withHealthStatus(TwinHealthStatus health) {
        return new TwinDeviceSnapshotResponse(deviceId, roomId, name, deviceType, icon, status, currentState, lastSeen, health);
    }
}
