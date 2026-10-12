package com.hesta.backend.diagnostics;

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
public class NodeDiagnosticsResponse {

    private UUID nodeId;
    private String nodeCode;
    private String status;
    private UUID homeId;
    private String homeName;
    private CircuitState circuitState;
    private int consecutiveFailures;
    private int totalCommands;
    private int successfulCommands;
    private long averageLatencyMs;
    private Long minLatencyMs;
    private Long maxLatencyMs;
    private Long avgLatencyMs;
    private Long p95LatencyMs;
    private OffsetDateTime lastPingAt;
    private OffsetDateTime lastTrippedAt;
}
