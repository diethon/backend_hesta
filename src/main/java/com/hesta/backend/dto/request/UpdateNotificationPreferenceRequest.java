package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotNull;

public record UpdateNotificationPreferenceRequest(
        @NotNull(message = "INVALID_REQUEST") Boolean securityEnabled,
        @NotNull(message = "INVALID_REQUEST") Boolean automationEnabled,
        @NotNull(message = "INVALID_REQUEST") Boolean systemEnabled
) {
}
