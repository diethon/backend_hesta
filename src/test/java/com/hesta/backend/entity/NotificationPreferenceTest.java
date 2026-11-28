package com.hesta.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

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

    @Test
    void isQuietAt_handlesWindowsWrappingPastMidnight() {
        NotificationPreference overnight = NotificationPreference.builder().quietHoursEnabled(true).build();

        assertThat(overnight.isQuietAt(LocalTime.of(23, 30))).isTrue();
        assertThat(overnight.isQuietAt(LocalTime.of(6, 59))).isTrue();
        assertThat(overnight.isQuietAt(LocalTime.of(7, 0))).isFalse();
        assertThat(overnight.isQuietAt(LocalTime.of(12, 0))).isFalse();

        NotificationPreference daytime = overnight.toBuilder()
                .quietHoursStart(LocalTime.of(13, 0)).quietHoursEnd(LocalTime.of(14, 0)).build();
        assertThat(daytime.isQuietAt(LocalTime.of(13, 30))).isTrue();
        assertThat(daytime.isQuietAt(LocalTime.of(14, 0))).isFalse();

        assertThat(NotificationPreference.defaults().isQuietAt(LocalTime.of(23, 0))).isFalse();
    }
}
