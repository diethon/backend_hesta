package com.hesta.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreference {

    @Column(name = "notify_security", nullable = false)
    @Builder.Default
    private boolean securityEnabled = true;

    @Column(name = "notify_automation", nullable = false)
    @Builder.Default
    private boolean automationEnabled = true;

    @Column(name = "notify_system", nullable = false)
    @Builder.Default
    private boolean systemEnabled = true;

    public static NotificationPreference defaults() {
        return NotificationPreference.builder().build();
    }
}
