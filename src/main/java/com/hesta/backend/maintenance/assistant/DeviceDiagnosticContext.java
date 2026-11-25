package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.MaintenanceTicketStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DeviceDiagnosticContext(
        UUID deviceId,
        String name,
        String deviceType,
        String model,
        String roomName,
        DeviceStatus status,
        OffsetDateTime lastSeen,
        Long offlineDurationMinutes,
        String offlineDurationLabel,
        String currentStateSummary,
        HealthSnapshot health,
        List<AnomalySnapshot> openAnomalies,
        List<HistorySnapshot> recentHistory,
        ActiveTicketSnapshot activeTicket,
        boolean homeWideOutage,
        int homeTotalDevices,
        int homeOfflineDevices
) {
    public DeviceDiagnosticContext(
            UUID deviceId,
            String name,
            String deviceType,
            String model,
            String roomName,
            DeviceStatus status,
            OffsetDateTime lastSeen,
            Long offlineDurationMinutes,
            String offlineDurationLabel,
            String currentStateSummary,
            HealthSnapshot health,
            List<AnomalySnapshot> openAnomalies,
            List<HistorySnapshot> recentHistory,
            ActiveTicketSnapshot activeTicket) {
        this(deviceId, name, deviceType, model, roomName, status, lastSeen,
                offlineDurationMinutes, offlineDurationLabel, currentStateSummary,
                health, openAnomalies, recentHistory, activeTicket, false, 0, 0);
    }
    public record HealthSnapshot(
            double healthPercent,
            int switchCycles,
            boolean chatteringWarning,
            boolean safetyTripped
    ) {
    }

    public record AnomalySnapshot(
            UUID id,
            String anomalyType,
            String severity,
            String reason,
            OffsetDateTime detectedAt
    ) {
    }

    public record HistorySnapshot(
            String source,
            Map<String, Object> newState,
            OffsetDateTime changedAt
    ) {
    }

    public record ActiveTicketSnapshot(
            UUID id,
            String code,
            MaintenanceTicketStatus status,
            String title
    ) {
    }
}
