package com.hesta.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Embeddable
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreference {

    public static final LocalTime DEFAULT_QUIET_START = LocalTime.of(22, 0);
    public static final LocalTime DEFAULT_QUIET_END = LocalTime.of(7, 0);

    @Column(name = "notify_security", nullable = false)
    @Builder.Default
    private boolean securityEnabled = true;

    @Column(name = "notify_automation", nullable = false)
    @Builder.Default
    private boolean automationEnabled = true;

    @Column(name = "notify_system", nullable = false)
    @Builder.Default
    private boolean systemEnabled = true;

    @Column(name = "quiet_hours_enabled", nullable = false)
    @Builder.Default
    private boolean quietHoursEnabled = false;

    @Column(name = "quiet_hours_start", nullable = false)
    @Builder.Default
    private LocalTime quietHoursStart = DEFAULT_QUIET_START;

    @Column(name = "quiet_hours_end", nullable = false)
    @Builder.Default
    private LocalTime quietHoursEnd = DEFAULT_QUIET_END;

    public static NotificationPreference defaults() {
        return NotificationPreference.builder().build();
    }

    /** True when {@code time} falls in [start, end); windows may wrap past midnight (22:00-07:00). */
    public boolean isQuietAt(LocalTime time) {
        if (!quietHoursEnabled || quietHoursStart.equals(quietHoursEnd)) {
            return false;
        }
        if (quietHoursStart.isBefore(quietHoursEnd)) {
            return !time.isBefore(quietHoursStart) && time.isBefore(quietHoursEnd);
        }
        return !time.isBefore(quietHoursStart) || time.isBefore(quietHoursEnd);
    }
}
