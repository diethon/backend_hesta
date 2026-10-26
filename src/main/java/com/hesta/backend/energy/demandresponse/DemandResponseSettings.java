package com.hesta.backend.energy.demandresponse;

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
public class DemandResponseSettings {
    private UUID homeId;
    private boolean enabled;
    private double peakThresholdWatts;
    private String mode; // AUTO_SHED, ALERT_ONLY
    private OffsetDateTime updatedAt;
}
