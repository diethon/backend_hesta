package com.hesta.backend.energy.demandresponse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemandResponseEvaluationResult {
    private UUID homeId;
    private double totalCurrentWatts;
    private double peakThresholdWatts;
    private double excessWatts;
    private String status; // NORMAL, WARNING_THRESHOLD, PEAK_SHEDDING_EXECUTED
    private List<String> sheddedDevices;
    private double wattsSaved;
    private String message;
    private OffsetDateTime evaluatedAt;
}
