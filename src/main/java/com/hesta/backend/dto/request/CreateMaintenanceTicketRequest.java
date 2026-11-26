package com.hesta.backend.dto.request;

import com.hesta.backend.enums.MaintenancePriority;
import com.hesta.backend.enums.MaintenanceTicketSource;
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
        List<@Valid @NotNull(message = "MAINTENANCE_INVALID_SLOT") MaintenanceTimeSlotRequest> slots,

        MaintenanceTicketSource source,

        @Size(max = 4000, message = "MAINTENANCE_INVALID_REQUEST")
        String diagnosisSummary,

        @Size(max = 5, message = "MAINTENANCE_ATTACHMENT_LIMIT_EXCEEDED")
        List<@NotNull(message = "MAINTENANCE_INVALID_REQUEST") UUID> attachmentIds,

        UUID chatConversationId,

        @Size(max = 2000, message = "MAINTENANCE_INVALID_REQUEST")
        String imageObservations
) {
    /** Backwards-compatible constructor for existing manual callers/tests. */
    public CreateMaintenanceTicketRequest(
            String title,
            String description,
            String contactPhone,
            MaintenancePriority priority,
            List<DeviceItem> devices,
            List<MaintenanceTimeSlotRequest> slots) {
        this(title, description, contactPhone, priority, devices, slots, null, null, null, null, null);
    }

    public CreateMaintenanceTicketRequest(
            String title,
            String description,
            String contactPhone,
            MaintenancePriority priority,
            List<DeviceItem> devices,
            List<MaintenanceTimeSlotRequest> slots,
            MaintenanceTicketSource source,
            String diagnosisSummary,
            List<UUID> attachmentIds,
            UUID chatConversationId) {
        this(title, description, contactPhone, priority, devices, slots, source, diagnosisSummary, attachmentIds, chatConversationId, null);
    }

    public record DeviceItem(
            @NotNull(message = "MAINTENANCE_INVALID_REQUEST") UUID deviceId,
            @Size(max = 1000, message = "MAINTENANCE_INVALID_REQUEST") String issueNote,
            UUID anomalyId
    ) {
    }
}
