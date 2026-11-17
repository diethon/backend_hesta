package com.hesta.backend.dto.event;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Event published within processTelemetry transaction, listened to AFTER_COMMIT
 * to trigger isolated anomaly evaluation without risking telemetry rollback.
 */
public record TelemetryAnomalyEvent(
        Long readingId,
        UUID deviceId,
        String metricType,
        BigDecimal value,
        OffsetDateTime recordedAt
) {}
