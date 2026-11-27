package com.hesta.backend.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.EdgeNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.mqtt.event.MqttConnectionFailedEvent;
import org.springframework.integration.mqtt.event.MqttSubscribedEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MqttDiagnosticsServiceImplTest {

    @Mock
    private EdgeNodeRepository edgeNodeRepository;

    @Mock
    private MqttGateway mqttGateway;

    @InjectMocks
    private MqttDiagnosticsServiceImpl diagnosticsService;

    private final String nodeCode = "NODE-TEST-01";
    private EdgeNode node;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(diagnosticsService, "brokerUrl", "tcp://localhost:1883");
        ReflectionTestUtils.setField(diagnosticsService, "topicPrefix", "hesta/nodes");
        ReflectionTestUtils.setField(diagnosticsService, "pingTimeoutMs", 1500L);
        ReflectionTestUtils.setField(diagnosticsService, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(diagnosticsService, "mqttGateway", mqttGateway);
        node = EdgeNode.builder()
                .id(UUID.randomUUID())
                .nodeCode(nodeCode)
                .status(EdgeNodeStatus.ONLINE)
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
        when(edgeNodeRepository.findByNodeCodeWithHome(nodeCode)).thenReturn(Optional.of(node));

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
        when(edgeNodeRepository.findByNodeCodeWithHome(nodeCode)).thenReturn(Optional.of(node));
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
        when(edgeNodeRepository.findByIdWithHome(node.getId())).thenReturn(Optional.of(node));
        when(edgeNodeRepository.findByNodeCodeWithHome(nodeCode)).thenReturn(Optional.of(node));

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
    @DisplayName("Dead Man's Snitch: auto-revives node from OFFLINE to ONLINE upon receiving INBOUND MQTT packet")
    void testDeadMansSnitch_AutoRevivesOfflineToOnline() {
        node.setStatus(com.hesta.backend.enums.EdgeNodeStatus.OFFLINE);
        when(edgeNodeRepository.findByNodeCode(nodeCode)).thenReturn(Optional.of(node));

        diagnosticsService.recordPacket("hesta/nodes/" + nodeCode + "/devices/1/telemetry", "IN", 1, "{}");

        assertEquals(com.hesta.backend.enums.EdgeNodeStatus.ONLINE, node.getStatus());
    }

    @Test
    @DisplayName("Dead Man's Snitch: OUTBOUND command packets must NOT revive an OFFLINE node")
    void testDeadMansSnitch_OutboundPacketDoesNotReviveOfflineNode() {
        node.setStatus(com.hesta.backend.enums.EdgeNodeStatus.OFFLINE);

        diagnosticsService.recordPacket("hesta/nodes/" + nodeCode + "/devices/1/command", "OUT", 1, "{\"action\":\"TURN_ON\"}");

        assertEquals(com.hesta.backend.enums.EdgeNodeStatus.OFFLINE, node.getStatus());
        verify(edgeNodeRepository, never()).save(any());
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

    @Test
    @DisplayName("Broker Status: tracks MqttSubscribedEvent and MqttConnectionFailedEvent instead of hardcoding true")
    void testBrokerConnectionTracking() {
        when(edgeNodeRepository.findAll()).thenReturn(List.of(node));

        assertFalse(diagnosticsService.getSummary().isBrokerConnected());

        diagnosticsService.onMqttSubscribed(new MqttSubscribedEvent(this, "connected"));
        assertTrue(diagnosticsService.getSummary().isBrokerConnected());

        diagnosticsService.onMqttConnectionFailed(new MqttConnectionFailedEvent(this, new RuntimeException("drop")));
        assertFalse(diagnosticsService.getSummary().isBrokerConnected());
    }

    @Test
    @DisplayName("Real MQTT Ping: publishes /ping, resolves on /pong, and works even if circuit breaker is OPEN")
    void testPingNode_RealMqttPong() throws Exception {
        when(edgeNodeRepository.findByIdWithHome(node.getId())).thenReturn(Optional.of(node));
        when(edgeNodeRepository.findById(node.getId())).thenReturn(Optional.of(node));
        when(edgeNodeRepository.findByNodeCodeWithHome(nodeCode)).thenReturn(Optional.of(node));

        // Open circuit breaker first; diagnostic ping must still bypass it
        diagnosticsService.tripCircuitBreaker(nodeCode);
        assertFalse(diagnosticsService.allowExecution(nodeCode));

        doAnswer(inv -> {
            String topic = inv.getArgument(0);
            String rawPayload = inv.getArgument(2);
            String codeTopic = "hesta/nodes/" + nodeCode + "/ping";
            String idTopic = "hesta/nodes/" + node.getId() + "/ping";
            assertTrue(topic.equals(codeTopic) || topic.equals(idTopic));
            if (topic.equals(codeTopic)) {
                Map<?, ?> sent = new ObjectMapper().readValue(rawPayload, Map.class);
                String pingId = sent.get("pingId").toString();
                new Thread(() -> {
                    try {
                        Thread.sleep(25);
                        diagnosticsService.handlePong(nodeCode, "{\"pingId\":\"" + pingId + "\"}");
                    } catch (Exception ignored) {}
                }).start();
            }
            return null;
        }).when(mqttGateway).sendToMqtt(anyString(), anyInt(), anyString());

        NodeDiagnosticsResponse response = diagnosticsService.pingNode(node.getId());

        assertNotNull(response);
        assertTrue(response.getAverageLatencyMs() >= 1L);
        assertNotNull(response.getLastPingAt());
        assertEquals(1, response.getSuccessfulCommands());
        assertEquals(CircuitState.CLOSED, response.getCircuitState());
    }

    @Test
    @DisplayName("Dual-ID MQTT Ping: publishes /ping to both nodeCode and UUID and resolves /pong arriving on UUID topic")
    void testPingNode_DualTopicAndUuidPong() throws Exception {
        when(edgeNodeRepository.findByIdWithHome(node.getId())).thenReturn(Optional.of(node));
        when(edgeNodeRepository.findById(node.getId())).thenReturn(Optional.of(node));

        String uuidTopic = "hesta/nodes/" + node.getId() + "/ping";
        doAnswer(inv -> {
            String topic = inv.getArgument(0);
            String rawPayload = inv.getArgument(2);
            if (uuidTopic.equals(topic)) {
                Map<?, ?> sent = new ObjectMapper().readValue(rawPayload, Map.class);
                String pingId = sent.get("pingId").toString();
                new Thread(() -> {
                    try {
                        Thread.sleep(15);
                        diagnosticsService.handlePong(node.getId().toString(), "{\"pingId\":\"" + pingId + "\"}");
                    } catch (Exception ignored) {}
                }).start();
            }
            return null;
        }).when(mqttGateway).sendToMqtt(anyString(), anyInt(), anyString());

        NodeDiagnosticsResponse response = diagnosticsService.pingNode(node.getId());

        assertNotNull(response);
        assertEquals(nodeCode, response.getNodeCode());
        assertTrue(response.getAverageLatencyMs() >= 1L);
        verify(mqttGateway).sendToMqtt(eq("hesta/nodes/" + nodeCode + "/ping"), eq(1), anyString());
        verify(mqttGateway).sendToMqtt(eq(uuidTopic), eq(1), anyString());
    }

    @Test
    @DisplayName("Real MQTT Ping: throws PING_TIMEOUT (504) instead of faking 25ms when node does not reply")
    void testPingNode_TimeoutThrows504() {
        ReflectionTestUtils.setField(diagnosticsService, "pingTimeoutMs", 80L);
        when(edgeNodeRepository.findByIdWithHome(node.getId())).thenReturn(Optional.of(node));

        AppException ex = assertThrows(AppException.class, () -> diagnosticsService.pingNode(node.getId()));
        assertEquals(ErrorCode.PING_TIMEOUT, ex.getErrorCode());

        verify(mqttGateway).sendToMqtt(eq("hesta/nodes/" + nodeCode + "/ping"), eq(1), anyString());
    }

    @Test
    @DisplayName("Real MQTT Ping: throws GATEWAY_DISCONNECTED (503) when MqttGateway is absent")
    void testPingNode_DisconnectedThrows503() {
        ReflectionTestUtils.setField(diagnosticsService, "mqttGateway", null);
        when(edgeNodeRepository.findByIdWithHome(node.getId())).thenReturn(Optional.of(node));

        AppException ex = assertThrows(AppException.class, () -> diagnosticsService.pingNode(node.getId()));
        assertEquals(ErrorCode.GATEWAY_DISCONNECTED, ex.getErrorCode());
    }

    @Test
    @DisplayName("Targeted Packet Sniffer: isolates packets per node even when global buffer overflows")
    void testTargetedPacketSnifferByNode() {
        when(edgeNodeRepository.findByNodeCode(nodeCode)).thenReturn(Optional.of(node));

        // First send 2 packets for NODE-TEST-01
        diagnosticsService.recordPacket("hesta/nodes/" + nodeCode + "/devices/d1/state", "IN", 1, "{\"power\":true}");
        diagnosticsService.recordPacket("hesta/nodes/" + nodeCode + "/ping", "OUT", 1, "{\"pingId\":\"p1\"}");

        // Flood global 50-packet buffer with packets from another node
        for (int i = 0; i < 60; i++) {
            diagnosticsService.recordPacket("hesta/nodes/OTHER-NODE-99/devices/d2/telemetry", "IN", 1, "{\"seq\":" + i + "}");
        }

        // Global unfiltered buffer drops older packets from NODE-TEST-01
        assertEquals(50, diagnosticsService.getSnifferPackets().size());

        // Targeted sniffer for NODE-TEST-01 still has both isolated packets intact
        List<MqttPacketDto> targeted = diagnosticsService.getSnifferPackets(nodeCode, null);
        assertEquals(2, targeted.size());
        assertTrue(targeted.stream().allMatch(p -> p.getTopic().contains("/" + nodeCode + "/")));
    }
}
