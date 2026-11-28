package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

/** Quiet-hours fields are optional so older clients keep their stored values. */
public record UpdateNotificationPreferenceRequest(
        @NotNull(message = "INVALID_REQUEST") Boolean securityEnabled,
        @NotNull(message = "INVALID_REQUEST") Boolean automationEnabled,
        @NotNull(message = "INVALID_REQUEST") Boolean systemEnabled,
        Boolean quietHoursEnabled,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd
) {
    public UpdateNotificationPreferenceRequest(Boolean securityEnabled, Boolean automationEnabled, Boolean systemEnabled) {
        this(securityEnabled, automationEnabled, systemEnabled, null, null, null);
    }
}
