package com.hesta.backend.entity;

import com.hesta.backend.enums.GuardianStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "parental_guardian_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParentalGuardianRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_id", nullable = false)
    private Home home;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_device_id", nullable = false)
    private Device targetDevice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camera_device_id")
    private Device cameraDevice;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "parent_away", nullable = false)
    @Builder.Default
    private boolean parentAway = false;

    @Column(name = "allowed_duration_minutes", nullable = false)
    @Builder.Default
    private int allowedDurationMinutes = 45;

    @Column(name = "warning_minutes_before", nullable = false)
    @Builder.Default
    private int warningMinutesBefore = 5;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private GuardianStatus status = GuardianStatus.IDLE;

    @Column(name = "session_started_at")
    private OffsetDateTime sessionStartedAt;

    @Column(name = "session_expires_at")
    private OffsetDateTime sessionExpiresAt;

    @Column(name = "auto_lockout", nullable = false)
    @Builder.Default
    private boolean autoLockout = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
