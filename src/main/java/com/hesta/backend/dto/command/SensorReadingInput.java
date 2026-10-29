package com.hesta.backend.dto.command;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Source-independent input to the provisional reading service; not a hardware protocol. */
public record SensorReadingInput(UUID deviceId, String metricType, BigDecimal value,
                                 String unit, OffsetDateTime observedAt) {
}
