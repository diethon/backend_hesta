package com.hesta.backend.dto.response;

/** latest is the decision made in the accepting transaction, not a delivery acknowledgement. */
public record SensorReadingAcceptedResponse(Long readingId, boolean latest, TwinSensorSnapshotResponse reading) {
}
