package com.hesta.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RescheduleMaintenanceRequest(
        @NotEmpty(message = "MAINTENANCE_INVALID_SLOT")
        @Size(max = 5, message = "MAINTENANCE_INVALID_SLOT")
        List<@Valid @NotNull(message = "MAINTENANCE_INVALID_SLOT") MaintenanceTimeSlotRequest> slots,

        @Size(max = 1000, message = "MAINTENANCE_INVALID_REQUEST")
        String note
) {
}
