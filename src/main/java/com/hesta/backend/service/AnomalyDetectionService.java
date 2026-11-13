package com.hesta.backend.service;

import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.AnomalyType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface AnomalyDetectionService {

    /**
     * Evaluates a single sensor reading against configured rule-based thresholds.
     * Executed within REQUIRES_NEW transaction to isolate anomaly errors from telemetry persistence.
     */
    void evaluateReading(Device device, String metric, BigDecimal value, OffsetDateTime timestamp, Long readingId);

    /**
     * Records that an anomaly of the given type was manually resolved by a user,
     * triggering a suppression cooldown window to avoid immediate re-alerting.
     */
    void recordManualResolution(UUID deviceId, AnomalyType anomalyType);
}
