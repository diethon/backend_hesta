package com.hesta.backend.ota;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ota_campaigns")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtaCampaignEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "firmware_package_id", nullable = false)
    private UUID firmwarePackageId;

    @Column(name = "device_type", nullable = false, length = 50)
    private String deviceType;

    @Column(name = "version", nullable = false, length = 50)
    private String version;

    @Column(name = "status", nullable = false, length = 40)
    private String status;

    @Column(name = "rollout_strategy", nullable = false, length = 30)
    private String rolloutStrategy;

    @Column(name = "failure_threshold_percent", nullable = false)
    private double failureThresholdPercent;

    @Column(name = "current_wave", nullable = false)
    @Builder.Default
    private int currentWave = 1;

    @Column(name = "total_nodes", nullable = false)
    private int totalNodes;

    @Column(name = "attempted_nodes", nullable = false)
    private int attemptedNodes;

    @Column(name = "success_nodes", nullable = false)
    private int successNodes;

    @Column(name = "failed_nodes", nullable = false)
    private int failedNodes;

    @Column(name = "abort_reason", columnDefinition = "text")
    private String abortReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
