package com.hesta.backend.dto.event;

import com.hesta.backend.enums.AnomalyType;
import com.hesta.backend.enums.SeverityLevel;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Domain event published when an anomaly is newly raised, escalated, or re-alerted.
 */
public record AnomalyRaisedEvent(
        UUID anomalyId,
        UUID homeId,
        UUID deviceId,
        String deviceName,
        AnomalyType anomalyType,
        SeverityLevel severity,
        String metric,
        String currentValue,
        String expectedRange,
        boolean isEscalated,
        OffsetDateTime detectedAt
) {}
