package com.hesta.backend.diagnostics;

import java.util.List;
import java.util.UUID;

public interface MqttDiagnosticsService {

    DiagnosticsSummaryResponse getSummary();

    List<NodeDiagnosticsResponse> getNodeDiagnostics();

    NodeDiagnosticsResponse resetCircuitBreaker(UUID nodeId);

    NodeDiagnosticsResponse pingNode(UUID nodeId);

    void recordCommandSuccess(String nodeCode, long latencyMs);

    void recordCommandFailure(String nodeCode);

    boolean allowExecution(String nodeCode);
}
