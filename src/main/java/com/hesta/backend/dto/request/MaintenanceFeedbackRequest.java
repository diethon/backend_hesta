package com.hesta.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MaintenanceFeedbackRequest(
        @NotNull(message = "MAINTENANCE_INVALID_REQUEST")
        @Min(value = 1, message = "MAINTENANCE_INVALID_REQUEST")
        @Max(value = 5, message = "MAINTENANCE_INVALID_REQUEST")
        Integer rating,

        @Size(max = 1000, message = "MAINTENANCE_INVALID_REQUEST")
        String comment
) {
}
