package com.hesta.backend.dto.request;

import com.hesta.backend.dto.command.SensorReadingInput;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Development-only input. The service validates all sources using the same rules. */
public record MockSensorReadingRequest(UUID deviceId, String metricType, BigDecimal value,
                                       String unit, OffsetDateTime observedAt) {
    public SensorReadingInput toInput() {
        return new SensorReadingInput(deviceId, metricType, value, unit, observedAt);
    }
}
