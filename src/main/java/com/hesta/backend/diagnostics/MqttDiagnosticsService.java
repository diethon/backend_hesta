package com.hesta.backend.diagnostics;

import java.util.List;
import java.util.UUID;

public interface MqttDiagnosticsService {

    DiagnosticsSummaryResponse getSummary();

    List<NodeDiagnosticsResponse> getNodeDiagnostics();

    NodeDiagnosticsResponse resetCircuitBreaker(UUID nodeId);

    NodeDiagnosticsResponse pingNode(UUID nodeId);

    void handlePong(String nodeCode, String payload);

    boolean isBrokerConnected();

    void recordCommandSuccess(String nodeCode, long latencyMs);

    void recordCommandFailure(String nodeCode);

    boolean allowExecution(String nodeCode);

    NodeDiagnosticsResponse tripCircuitBreaker(String nodeCode);

    NodeDiagnosticsResponse tripCircuitBreaker(UUID nodeId);

    void recordPacket(String topic, String direction, int qos, String payload);

    List<MqttPacketDto> getSnifferPackets();

    default List<MqttPacketDto> getSnifferPackets(String nodeCode, UUID homeId) {
        return getSnifferPackets();
    }
}
