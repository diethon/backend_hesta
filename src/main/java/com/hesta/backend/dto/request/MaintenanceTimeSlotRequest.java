package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record MaintenanceTimeSlotRequest(
        @NotNull(message = "MAINTENANCE_INVALID_SLOT") OffsetDateTime startAt,
        @NotNull(message = "MAINTENANCE_INVALID_SLOT") OffsetDateTime endAt
) {
}
