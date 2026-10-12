package com.hesta.backend.diagnostics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosticsSummaryResponse {

    private boolean brokerConnected;
    private String brokerUrl;
    private int totalNodes;
    private int onlineNodes;
    private int offlineNodes;
    private int quarantinedNodes;
    private double systemReliabilityPct;
    private long averageLatencyMs;
}
