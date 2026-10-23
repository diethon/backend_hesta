package com.hesta.backend.ota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOtaCampaignRequest {
    private String name;
    private UUID firmwarePackageId;
    private Double failureThresholdPercent;
    private String rolloutStrategy; // CANARY, IMMEDIATE
}
