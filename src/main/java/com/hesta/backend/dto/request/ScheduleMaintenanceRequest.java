package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record ScheduleMaintenanceRequest(
        @NotNull(message = "MAINTENANCE_INVALID_SLOT") OffsetDateTime startAt,
        @NotNull(message = "MAINTENANCE_INVALID_SLOT") OffsetDateTime endAt,
        @Size(max = 1000, message = "MAINTENANCE_INVALID_REQUEST") String note
) {
}
