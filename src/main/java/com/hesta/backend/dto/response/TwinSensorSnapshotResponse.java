package com.hesta.backend.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One device/metric stream, also used as SENSOR_READING_UPDATED data. */
public record TwinSensorSnapshotResponse(
        String sensorId, UUID roomId, UUID deviceId, String metricType,
        BigDecimal latestValue, String unit, OffsetDateTime observedAt
) {
}
