package com.hesta.backend.ota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtaCampaignDto {
    private UUID id;
    private String name;
    private UUID firmwarePackageId;
    private String deviceType;
    private String version;
    private String status; // DRAFT, RUNNING_CANARY_10, RUNNING_CANARY_50, RUNNING_FULL_100, COMPLETED, ABORTED_FAILURE_THRESHOLD, CANCELLED
    private String rolloutStrategy;
    private double failureThresholdPercent;
    private int totalNodes;
    private int attemptedNodes;
    private int successNodes;
    private int failedNodes;
    private String abortReason;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
