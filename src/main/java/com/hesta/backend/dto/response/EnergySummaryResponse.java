package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnergySummaryResponse {
    private double totalKwh;
    private long estimatedCostVnd;
    private int currentTier; // 1 to 6 EVN tariff tier
    private double projectedMonthKwh;
    private long projectedMonthCostVnd;
    private double comparisonLastPeriodPercent;
    private String period; // "24H", "7D", "30D", "MONTHLY"
    private int activeDeviceCount;
    private int totalDeviceCount;
}
