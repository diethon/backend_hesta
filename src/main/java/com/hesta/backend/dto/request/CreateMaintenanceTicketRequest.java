package com.hesta.backend.dto.request;

import com.hesta.backend.enums.MaintenancePriority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateMaintenanceTicketRequest(
        @NotBlank(message = "MAINTENANCE_INVALID_REQUEST")
        @Size(max = 150, message = "MAINTENANCE_INVALID_REQUEST")
        String title,

        @Size(max = 2000, message = "MAINTENANCE_INVALID_REQUEST")
        String description,

        @Size(max = 20, message = "MAINTENANCE_INVALID_REQUEST")
        String contactPhone,

        MaintenancePriority priority,

        @NotEmpty(message = "MAINTENANCE_INVALID_REQUEST")
        @Size(max = 10, message = "MAINTENANCE_INVALID_REQUEST")
        List<@Valid @NotNull(message = "MAINTENANCE_INVALID_REQUEST") DeviceItem> devices,

        @NotEmpty(message = "MAINTENANCE_INVALID_SLOT")
        @Size(max = 5, message = "MAINTENANCE_INVALID_SLOT")
        List<@Valid @NotNull(message = "MAINTENANCE_INVALID_SLOT") MaintenanceTimeSlotRequest> slots
) {
    public record DeviceItem(
            @NotNull(message = "MAINTENANCE_INVALID_REQUEST") UUID deviceId,
            @Size(max = 1000, message = "MAINTENANCE_INVALID_REQUEST") String issueNote,
            UUID anomalyId
    ) {
    }
}
