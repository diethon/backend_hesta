package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnergyAnalyticsResponse {
    private EnergySummaryResponse summary;
    private List<EnergyTimeSeriesPoint> timeSeries;
    private List<DeviceEnergyBreakdown> breakdown;
}
