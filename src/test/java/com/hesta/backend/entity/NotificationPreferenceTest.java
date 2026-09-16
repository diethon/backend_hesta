package com.hesta.backend.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPreferenceTest {

    @Test
    void defaults_enableBaselineNotificationCategories() {
        NotificationPreference preference = NotificationPreference.defaults();

        assertThat(preference.isSecurityEnabled()).isTrue();
        assertThat(preference.isAutomationEnabled()).isTrue();
        assertThat(preference.isSystemEnabled()).isTrue();
    }

    @Test
    void userPreference_withoutExplicitNotificationPreference_usesBaselineDefaults() {
        UserPreference preference = UserPreference.builder().build();

        assertThat(preference.getNotificationPreference()).isNotNull();
        assertThat(preference.getNotificationPreference().isSecurityEnabled()).isTrue();
    }
}
