package com.hesta.backend.diagnostics;

import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.repository.EdgeNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MqttDiagnosticsServiceImplTest {

    @Mock
    private EdgeNodeRepository edgeNodeRepository;

    @InjectMocks
    private MqttDiagnosticsServiceImpl diagnosticsService;

    private final String nodeCode = "NODE-TEST-01";
    private EdgeNode node;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(diagnosticsService, "brokerUrl", "tcp://localhost:1883");
        node = EdgeNode.builder()
                .id(UUID.randomUUID())
                .nodeCode(nodeCode)
                .build();
    }

    @Test
    @DisplayName("RTT Buffer: calculates min, max, avg, and p95 latency accurately")
    void testRttMetricsBuffer() {
        when(edgeNodeRepository.findAll()).thenReturn(List.of(node));

        // Add 10 latency measurements: 10, 20, 30, 40, 50, 60, 70, 80, 90, 100
        for (long i = 10; i <= 100; i += 10) {
            diagnosticsService.recordCommandSuccess(nodeCode, i);
        }

        DiagnosticsSummaryResponse summary = diagnosticsService.getSummary();

        assertNotNull(summary);
        assertEquals(10L, summary.getMinLatencyMs());
        assertEquals(100L, summary.getMaxLatencyMs());
        assertEquals(55L, summary.getAvgLatencyMs());
        assertNotNull(summary.getP95LatencyMs());
        assertTrue(summary.getP95LatencyMs() >= 90L);
    }

    @Test
    @DisplayName("Packet Sniffer Buffer: keeps at most 50 packets in FIFO/LIFO window")
    void testPacketSnifferBuffer() {
        for (int i = 1; i <= 60; i++) {
            diagnosticsService.recordPacket("hesta/nodes/test/device/1/state", "IN", 1, "payload-" + i);
        }

        List<MqttPacketDto> packets = diagnosticsService.getSnifferPackets();

        assertEquals(50, packets.size());
        assertEquals("payload-60", packets.get(0).getPayloadSummary());
        assertNotNull(packets.get(0).getId());
        assertEquals("INBOUND", packets.get(0).getDirection());

        // Test outbound packet normalization
        diagnosticsService.recordPacket("hesta/nodes/test/devices/1/command", "OUT", 1, "{\"power\":\"ON\"}");
        MqttPacketDto latest = diagnosticsService.getSnifferPackets().get(0);
        assertEquals("OUTBOUND", latest.getDirection());
        assertNotNull(latest.getId());
    }

    @Test
    @DisplayName("Circuit Breaker: trip endpoint forces OPEN state and blocks commands")
    void testTripCircuitBreaker() {
        when(edgeNodeRepository.findByNodeCode(nodeCode)).thenReturn(Optional.of(node));

        assertTrue(diagnosticsService.allowExecution(nodeCode));

        NodeDiagnosticsResponse response = diagnosticsService.tripCircuitBreaker(nodeCode);

        assertNotNull(response);
        assertEquals(CircuitState.OPEN, response.getCircuitState());
        assertFalse(diagnosticsService.allowExecution(nodeCode));
    }

    @Test
    @DisplayName("Circuit Breaker: automatically trips to OPEN after 3 consecutive failures")
    void testCircuitBreaker_TripsAfterThresholdFailures() {
        assertTrue(diagnosticsService.allowExecution(nodeCode));

        diagnosticsService.recordCommandFailure(nodeCode);
        assertTrue(diagnosticsService.allowExecution(nodeCode)); // 1 failure -> still closed

        diagnosticsService.recordCommandFailure(nodeCode);
        assertTrue(diagnosticsService.allowExecution(nodeCode)); // 2 failures -> still closed

        diagnosticsService.recordCommandFailure(nodeCode);
        assertFalse(diagnosticsService.allowExecution(nodeCode)); // 3 failures -> TRIPPED to OPEN
    }

    @Test
    @DisplayName("Circuit Breaker: in HALF_OPEN state, only one trial execution in flight is permitted")
    void testCircuitBreaker_HalfOpenOnlyAllowsSingleTrial() {
        // Manually trip
        when(edgeNodeRepository.findByNodeCode(nodeCode)).thenReturn(Optional.of(node));
        diagnosticsService.tripCircuitBreaker(nodeCode);
        assertFalse(diagnosticsService.allowExecution(nodeCode));

        // Advance cooldown by simulating 61 seconds in the past
        Object tracker = org.springframework.test.util.ReflectionTestUtils.invokeMethod(diagnosticsService, "getTracker", nodeCode);
        assertNotNull(tracker);
        org.springframework.test.util.ReflectionTestUtils.setField(tracker, "lastTrippedAt", java.time.OffsetDateTime.now().minusSeconds(65));

        // First execution in HALF_OPEN -> permitted
        assertTrue(diagnosticsService.allowExecution(nodeCode));

        // Second concurrent execution in HALF_OPEN -> blocked (prevent thundering herd)
        assertFalse(diagnosticsService.allowExecution(nodeCode));

        // If the trial command succeeds -> restored to CLOSED
        diagnosticsService.recordCommandSuccess(nodeCode, 35L);
        assertTrue(diagnosticsService.allowExecution(nodeCode));
    }

    @Test
    @DisplayName("Circuit Breaker: trip endpoint with UUID nodeId resolves node and forces OPEN state")
    void testTripCircuitBreaker_ByNodeId() {
        when(edgeNodeRepository.findById(node.getId())).thenReturn(Optional.of(node));
        when(edgeNodeRepository.findByNodeCode(nodeCode)).thenReturn(Optional.of(node));

        assertTrue(diagnosticsService.allowExecution(nodeCode));

        NodeDiagnosticsResponse response = diagnosticsService.tripCircuitBreaker(node.getId());

        assertNotNull(response);
        assertEquals(CircuitState.OPEN, response.getCircuitState());
        assertEquals(node.getId(), response.getNodeId());
        assertEquals(nodeCode, response.getNodeCode());
        assertFalse(diagnosticsService.allowExecution(nodeCode));
    }

    @Test
    @DisplayName("Dead Man's Snitch: marks node OFFLINE when no heartbeat received for > 60s")
    void testDeadMansSnitch_TimesOutAfter60Seconds() {
        node.setStatus(com.hesta.backend.enums.EdgeNodeStatus.ONLINE);
        when(edgeNodeRepository.findAll()).thenReturn(List.of(node));

        // Node receives a packet to initialize its tracker
        diagnosticsService.recordPacket("hesta/nodes/" + nodeCode + "/devices/1/telemetry", "IN", 1, "{}");

        // Simulate last heartbeat 65 seconds ago
        Object tracker = org.springframework.test.util.ReflectionTestUtils.invokeMethod(diagnosticsService, "getTracker", nodeCode);
        assertNotNull(tracker);
        org.springframework.test.util.ReflectionTestUtils.setField(tracker, "lastPingAt", java.time.OffsetDateTime.now().minusSeconds(65));

        // Run Dead Man's Snitch
        diagnosticsService.evaluateDeadMansSnitch();

        // Node should be marked OFFLINE
        assertEquals(com.hesta.backend.enums.EdgeNodeStatus.OFFLINE, node.getStatus());
    }

    @Test
    @DisplayName("Record Packet: ignores broadcast and unknown topics without hitting repository")
    void testRecordPacket_IgnoresBroadcastAndUnknownTopic() {
        diagnosticsService.recordPacket("hesta/nodes/broadcast/request_state", "OUT", 1, "{}");
        diagnosticsService.recordPacket("hesta/nodes/unknown/devices/1/command", "OUT", 1, "{}");

        // Broadcast or unknown must not create trackers or crash
        List<MqttPacketDto> packets = diagnosticsService.getSnifferPackets();
        assertEquals(2, packets.size());
    }

    @Test
    @DisplayName("Dead Man's Snitch: auto-revives node from OFFLINE to ONLINE upon receiving MQTT packet")
    void testDeadMansSnitch_AutoRevivesOfflineToOnline() {
        node.setStatus(com.hesta.backend.enums.EdgeNodeStatus.OFFLINE);
        when(edgeNodeRepository.findByNodeCode(nodeCode)).thenReturn(Optional.of(node));

        diagnosticsService.recordPacket("hesta/nodes/" + nodeCode + "/devices/1/telemetry", "IN", 1, "{}");

        assertEquals(com.hesta.backend.enums.EdgeNodeStatus.ONLINE, node.getStatus());
    }

    @Test
    @DisplayName("Circuit Breaker: reset preserves actual lastPingAt instead of faking fresh heartbeat")
    void testResetCircuitBreaker_PreservesLastPingAt() {
        when(edgeNodeRepository.findById(node.getId())).thenReturn(Optional.of(node));

        // Node has not pinged yet
        NodeDiagnosticsResponse response = diagnosticsService.resetCircuitBreaker(node.getId());

        assertNotNull(response);
        assertNull(response.getLastPingAt(), "Resetting circuit breaker must not fake lastPingAt when node hasn't pinged");
        assertEquals(CircuitState.CLOSED, response.getCircuitState());
    }
}
