package com.hesta.backend.dto.response;

import java.util.List;
import java.util.UUID;

public record TwinRoomSnapshotResponse(
        UUID roomId, UUID homeId, String name, String icon,
        List<TwinDeviceSnapshotResponse> devices, List<TwinSensorSnapshotResponse> sensors
) {
}
