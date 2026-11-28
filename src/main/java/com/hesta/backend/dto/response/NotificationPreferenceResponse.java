package com.hesta.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hesta.backend.entity.NotificationPreference;

import java.time.LocalTime;

public record NotificationPreferenceResponse(
        boolean securityEnabled,
        boolean automationEnabled,
        boolean systemEnabled,
        boolean quietHoursEnabled,
        @JsonFormat(pattern = "HH:mm") LocalTime quietHoursStart,
        @JsonFormat(pattern = "HH:mm") LocalTime quietHoursEnd
) {
    public static NotificationPreferenceResponse from(NotificationPreference preference) {
        return new NotificationPreferenceResponse(
                preference.isSecurityEnabled(),
                preference.isAutomationEnabled(),
                preference.isSystemEnabled(),
                preference.isQuietHoursEnabled(),
                preference.getQuietHoursStart(),
                preference.getQuietHoursEnd()
        );
    }
}
