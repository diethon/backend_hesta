package com.hesta.backend.dto.command;

import com.hesta.backend.dto.response.TwinDeviceSnapshotResponse;
import java.util.UUID;

public record DeviceStateChangedEvent(UUID homeId, TwinDeviceSnapshotResponse payload) {
}
