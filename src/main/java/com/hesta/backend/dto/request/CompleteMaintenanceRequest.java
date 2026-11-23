package com.hesta.backend.dto.request;

import com.hesta.backend.enums.MaintenanceDeviceOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Kết luận cuối: mỗi thiết bị trong ticket phải có đúng một outcome. */
public record CompleteMaintenanceRequest(
        @NotEmpty(message = "MAINTENANCE_INVALID_REQUEST")
        List<@Valid @NotNull(message = "MAINTENANCE_INVALID_REQUEST") DeviceOutcome> devices,

        @Size(max = 2000, message = "MAINTENANCE_INVALID_REQUEST")
        String note
) {
    public record DeviceOutcome(
            @NotNull(message = "MAINTENANCE_INVALID_REQUEST") UUID ticketDeviceId,
            @NotNull(message = "MAINTENANCE_INVALID_REQUEST") MaintenanceDeviceOutcome outcome,
            @Size(max = 1000, message = "MAINTENANCE_INVALID_REQUEST") String note
    ) {
    }
}
