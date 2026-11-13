package com.hesta.backend.dto.response;

import java.util.List;
import java.util.UUID;

public record TwinHomeSnapshotResponse(
        UUID homeId, String name, List<TwinRoomSnapshotResponse> rooms,
        List<TwinDeviceSnapshotResponse> unassignedDevices,
        List<TwinSensorSnapshotResponse> unassignedSensors
) {
}
