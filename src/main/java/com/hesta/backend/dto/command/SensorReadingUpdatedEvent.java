package com.hesta.backend.dto.command;

import com.hesta.backend.dto.response.TwinSensorSnapshotResponse;
import java.util.UUID;

/** Emit inside the transaction that accepts a new latest reading. */
public record SensorReadingUpdatedEvent(UUID homeId, TwinSensorSnapshotResponse payload) {
}
