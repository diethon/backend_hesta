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
}
