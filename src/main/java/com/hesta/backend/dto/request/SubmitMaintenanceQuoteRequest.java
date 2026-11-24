package com.hesta.backend.dto.request;

import com.hesta.backend.enums.MaintenanceItemType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SubmitMaintenanceQuoteRequest(
        @NotBlank(message = "MAINTENANCE_INVALID_REQUEST")
        @Size(max = 4000, message = "MAINTENANCE_INVALID_REQUEST")
        String findings,

        @Min(value = 0, message = "MAINTENANCE_INVALID_REQUEST")
        @Max(value = 60, message = "MAINTENANCE_INVALID_REQUEST")
        Integer warrantyMonths,

        @NotEmpty(message = "MAINTENANCE_INVALID_REQUEST")
        @Size(max = 50, message = "MAINTENANCE_INVALID_REQUEST")
        List<@Valid @NotNull(message = "MAINTENANCE_INVALID_REQUEST") Item> items
) {
    public record Item(
            /** Optional: the ticket device this line belongs to. */
            UUID ticketDeviceId,

            @NotNull(message = "MAINTENANCE_INVALID_REQUEST")
            MaintenanceItemType itemType,

            @NotBlank(message = "MAINTENANCE_INVALID_REQUEST")
            @Size(max = 255, message = "MAINTENANCE_INVALID_REQUEST")
            String description,

            @NotNull(message = "MAINTENANCE_INVALID_REQUEST")
            @Min(value = 1, message = "MAINTENANCE_INVALID_REQUEST")
            @Max(value = 1000, message = "MAINTENANCE_INVALID_REQUEST")
            Integer quantity,

            @NotNull(message = "MAINTENANCE_INVALID_REQUEST")
            @DecimalMin(value = "0", message = "MAINTENANCE_INVALID_REQUEST")
            @Digits(integer = 10, fraction = 2, message = "MAINTENANCE_INVALID_REQUEST")
            BigDecimal unitPrice
    ) {
    }
}
