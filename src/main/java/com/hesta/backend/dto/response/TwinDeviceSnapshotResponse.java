package com.hesta.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.DeviceType;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Also the complete single-node data payload for DEVICE_STATE_CHANGED. */
public record TwinDeviceSnapshotResponse(
        UUID deviceId, UUID roomId, String name, DeviceType deviceType, String icon,
        DeviceStatus status, JsonNode currentState, OffsetDateTime lastSeen
) {
}
