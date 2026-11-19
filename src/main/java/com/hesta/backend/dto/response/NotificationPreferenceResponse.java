package com.hesta.backend.dto.response;

import com.hesta.backend.entity.NotificationPreference;

public record NotificationPreferenceResponse(
        boolean securityEnabled,
        boolean automationEnabled,
        boolean systemEnabled
) {
    public static NotificationPreferenceResponse from(NotificationPreference preference) {
        return new NotificationPreferenceResponse(
                preference.isSecurityEnabled(),
                preference.isAutomationEnabled(),
                preference.isSystemEnabled()
        );
    }
}
