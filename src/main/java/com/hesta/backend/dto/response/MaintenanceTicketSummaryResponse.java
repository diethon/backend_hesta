package com.hesta.backend.dto.response;

import com.hesta.backend.entity.MaintenanceTicket;
import com.hesta.backend.entity.MaintenanceTicketDevice;
import com.hesta.backend.enums.MaintenancePriority;
import com.hesta.backend.enums.MaintenanceTicketSource;
import com.hesta.backend.enums.MaintenanceTicketStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Dòng ticket cho danh sách / lịch. */
public record MaintenanceTicketSummaryResponse(
        UUID id,
        String code,
        UUID homeId,
        String homeName,
        String title,
        MaintenancePriority priority,
        MaintenanceTicketStatus status,
        MaintenanceTicketSource source,
        List<String> deviceNames,
        OffsetDateTime scheduledStart,
        OffsetDateTime scheduledEnd,
        boolean scheduleConfirmed,
        BigDecimal quoteTotal,
        String createdByName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static MaintenanceTicketSummaryResponse from(MaintenanceTicket t) {
        return new MaintenanceTicketSummaryResponse(
                t.getId(),
                t.getCode(),
                t.getHome() != null ? t.getHome().getId() : null,
                t.getHome() != null ? t.getHome().getName() : null,
                t.getTitle(),
                t.getPriority(),
                t.getStatus(),
                t.getSource() != null ? t.getSource() : MaintenanceTicketSource.MANUAL,
                t.getDevices().stream().map(MaintenanceTicketDevice::getDeviceName).toList(),
                t.getScheduledStart(),
                t.getScheduledEnd(),
                t.isScheduleConfirmed(),
                t.getQuoteTotal(),
                t.getCreatedBy() != null ? t.getCreatedBy().getFullName() : null,
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
