package com.hesta.backend.diagnostics;

import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.EdgeNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MqttDiagnosticsServiceImpl implements MqttDiagnosticsService {

    private final EdgeNodeRepository edgeNodeRepository;

    @Value("${mqtt.broker.url:tcp://localhost:1883}")
    private String brokerUrl;

    private static final int FAILURE_THRESHOLD = 3;
    private static final long COOLDOWN_SECONDS = 60;
    private static final int RTT_BUFFER_SIZE = 100;
    private static final int SNIFFER_BUFFER_SIZE = 50;

    private final ConcurrentHashMap<String, NodeHealthTracker> trackers = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> globalRttBuffer = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<MqttPacketDto> snifferBuffer = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private static class NodeHealthTracker {
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        final AtomicInteger totalCommands = new AtomicInteger(0);
        final AtomicInteger successfulCommands = new AtomicInteger(0);
        final AtomicLong totalLatencyMs = new AtomicLong(0);
        final java.util.concurrent.ConcurrentLinkedDeque<Long> nodeRttBuffer = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
        volatile OffsetDateTime lastTrippedAt = null;
        volatile OffsetDateTime lastPingAt = OffsetDateTime.now();
    }

    private NodeHealthTracker getTracker(String nodeCode) {
        return trackers.computeIfAbsent(nodeCode, k -> new NodeHealthTracker());
    }

    private long[] calculateLatencyStats(java.util.Collection<Long> samples) {
        if (samples == null || samples.isEmpty()) {
            return new long[]{0, 0, 0, 0};
        }
        java.util.List<Long> list = new java.util.ArrayList<>(samples);
        list.sort(Long::compareTo);
        long min = list.get(0);
        long max = list.get(list.size() - 1);
        double sum = 0;
        for (Long v : list) sum += v;
        long avg = Math.round(sum / list.size());
        int p95Index = (int) Math.ceil(0.95 * list.size()) - 1;
        if (p95Index < 0) p95Index = 0;
        if (p95Index >= list.size()) p95Index = list.size() - 1;
        long p95 = list.get(p95Index);
        return new long[]{min, max, avg, p95};
    }

    @Override
    public boolean allowExecution(String nodeCode) {
        if (nodeCode == null) return true;
        NodeHealthTracker tracker = getTracker(nodeCode);
        CircuitState currentState = tracker.state.get();

        if (currentState == CircuitState.CLOSED) {
            return true;
        }

        if (currentState == CircuitState.OPEN) {
            if (tracker.lastTrippedAt != null &&
                    Duration.between(tracker.lastTrippedAt, OffsetDateTime.now()).getSeconds() > COOLDOWN_SECONDS) {
                tracker.state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN);
                log.info("Circuit breaker for node {} transitioned to HALF_OPEN (trial execution allowed)", nodeCode);
                return true;
            }
            return false;
        }

        // HALF_OPEN allows single trial execution
        return true;
    }

    @Override
    public void recordCommandSuccess(String nodeCode, long latencyMs) {
        if (nodeCode == null) return;
        NodeHealthTracker tracker = getTracker(nodeCode);
        tracker.totalCommands.incrementAndGet();
        tracker.successfulCommands.incrementAndGet();
        tracker.totalLatencyMs.addAndGet(latencyMs);
        tracker.consecutiveFailures.set(0);

        // Record in node and global RTT buffers
        tracker.nodeRttBuffer.addFirst(latencyMs);
        while (tracker.nodeRttBuffer.size() > RTT_BUFFER_SIZE) {
            tracker.nodeRttBuffer.pollLast();
        }

        globalRttBuffer.addFirst(latencyMs);
        while (globalRttBuffer.size() > RTT_BUFFER_SIZE) {
            globalRttBuffer.pollLast();
        }

        if (tracker.state.get() != CircuitState.CLOSED) {
            tracker.state.set(CircuitState.CLOSED);
            log.info("Circuit breaker for node {} restored to CLOSED (Healthy)", nodeCode);
        }
    }

    @Override
    public void recordCommandFailure(String nodeCode) {
        if (nodeCode == null) return;
        NodeHealthTracker tracker = getTracker(nodeCode);
        tracker.totalCommands.incrementAndGet();
        int fails = tracker.consecutiveFailures.incrementAndGet();

        // Fix survivorship bias: record timeout latency (5000ms) so P95 reflects real network degradation
        long timeoutLatency = 5000L;
        tracker.totalLatencyMs.addAndGet(timeoutLatency);
        tracker.nodeRttBuffer.addFirst(timeoutLatency);
        while (tracker.nodeRttBuffer.size() > RTT_BUFFER_SIZE) {
            tracker.nodeRttBuffer.pollLast();
        }
        globalRttBuffer.addFirst(timeoutLatency);
        while (globalRttBuffer.size() > RTT_BUFFER_SIZE) {
            globalRttBuffer.pollLast();
        }

        if (fails >= FAILURE_THRESHOLD) {
            if (tracker.state.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN) ||
                tracker.state.compareAndSet(CircuitState.HALF_OPEN, CircuitState.OPEN)) {
                tracker.lastTrippedAt = OffsetDateTime.now();
                log.warn("Circuit breaker for node {} TRIPPED to OPEN after {} consecutive failures", nodeCode, fails);
            }
        }
    }

    @Override
    public NodeDiagnosticsResponse tripCircuitBreaker(String nodeCode) {
        EdgeNode node = edgeNodeRepository.findByNodeCode(nodeCode).orElse(null);
        NodeHealthTracker tracker = getTracker(nodeCode);
        tracker.state.set(CircuitState.OPEN);
        tracker.lastTrippedAt = OffsetDateTime.now();
        tracker.consecutiveFailures.set(FAILURE_THRESHOLD);
        log.warn("Circuit breaker for node {} was manually TRIPPED to OPEN via diagnostics endpoint", nodeCode);

        long[] stats = calculateLatencyStats(tracker.nodeRttBuffer.isEmpty() ? globalRttBuffer : tracker.nodeRttBuffer);

        return NodeDiagnosticsResponse.builder()
                .nodeId(node != null ? node.getId() : null)
                .nodeCode(nodeCode)
                .status(node != null && node.getStatus() != null ? node.getStatus().name() : "OFFLINE")
                .homeId(node != null && node.getHome() != null ? node.getHome().getId() : null)
                .homeName(node != null && node.getHome() != null ? node.getHome().getName() : "N/A")
                .circuitState(CircuitState.OPEN)
                .consecutiveFailures(tracker.consecutiveFailures.get())
                .totalCommands(tracker.totalCommands.get())
                .successfulCommands(tracker.successfulCommands.get())
                .averageLatencyMs(stats[2])
                .minLatencyMs(stats[0])
                .maxLatencyMs(stats[1])
                .avgLatencyMs(stats[2])
                .p95LatencyMs(stats[3])
                .lastPingAt(tracker.lastPingAt)
                .lastTrippedAt(tracker.lastTrippedAt)
                .build();
    }

    @Override
    public void recordPacket(String topic, String direction, int qos, String payload) {
        String summary = payload;
        if (summary != null && summary.length() > 200) {
            summary = summary.substring(0, 197) + "...";
        }
        MqttPacketDto packet = MqttPacketDto.builder()
                .timestamp(OffsetDateTime.now())
                .topic(topic)
                .direction(direction != null ? direction : "IN")
                .qos(qos)
                .payloadSummary(summary)
                .build();
        snifferBuffer.addFirst(packet);
        while (snifferBuffer.size() > SNIFFER_BUFFER_SIZE) {
            snifferBuffer.pollLast();
        }
    }

    @Override
    public java.util.List<MqttPacketDto> getSnifferPackets() {
        return new java.util.ArrayList<>(snifferBuffer);
    }

    @Override
    public DiagnosticsSummaryResponse getSummary() {
        List<EdgeNode> allNodes = edgeNodeRepository.findAll();
        int total = allNodes.size();
        int online = (int) allNodes.stream().filter(n -> n.getStatus() == EdgeNodeStatus.ONLINE).count();
        int offline = total - online;

        int quarantined = 0;
        int totalCmds = 0;
        int successCmds = 0;

        for (EdgeNode node : allNodes) {
            NodeHealthTracker t = trackers.get(node.getNodeCode());
            if (t != null) {
                if (t.state.get() == CircuitState.OPEN) quarantined++;
                totalCmds += t.totalCommands.get();
                successCmds += t.successfulCommands.get();
            }
        }

        double reliability = totalCmds > 0 ? ((double) successCmds / totalCmds) * 100.0 : 100.0;
        long[] stats = calculateLatencyStats(globalRttBuffer);

        return DiagnosticsSummaryResponse.builder()
                .brokerConnected(true)
                .brokerUrl(brokerUrl)
                .totalNodes(total)
                .onlineNodes(online)
                .offlineNodes(offline)
                .quarantinedNodes(quarantined)
                .systemReliabilityPct(Math.round(reliability * 10.0) / 10.0)
                .averageLatencyMs(stats[2])
                .minLatencyMs(stats[0])
                .maxLatencyMs(stats[1])
                .avgLatencyMs(stats[2])
                .p95LatencyMs(stats[3])
                .build();
    }

    @Override
    public List<NodeDiagnosticsResponse> getNodeDiagnostics() {
        List<EdgeNode> allNodes = edgeNodeRepository.findAll();
        return allNodes.stream().map(node -> {
            NodeHealthTracker t = getTracker(node.getNodeCode());
            int total = t.totalCommands.get();
            int succ = t.successfulCommands.get();
            long[] stats = calculateLatencyStats(t.nodeRttBuffer.isEmpty() ? globalRttBuffer : t.nodeRttBuffer);

            return NodeDiagnosticsResponse.builder()
                    .nodeId(node.getId())
                    .nodeCode(node.getNodeCode())
                    .status(node.getStatus() != null ? node.getStatus().name() : "OFFLINE")
                    .homeId(node.getHome() != null ? node.getHome().getId() : null)
                    .homeName(node.getHome() != null ? node.getHome().getName() : "N/A")
                    .circuitState(t.state.get())
                    .consecutiveFailures(t.consecutiveFailures.get())
                    .totalCommands(total)
                    .successfulCommands(succ)
                    .averageLatencyMs(stats[2])
                    .minLatencyMs(stats[0])
                    .maxLatencyMs(stats[1])
                    .avgLatencyMs(stats[2])
                    .p95LatencyMs(stats[3])
                    .lastPingAt(t.lastPingAt)
                    .lastTrippedAt(t.lastTrippedAt)
                    .build();
        }).collect(Collectors.toList());
    }

    @Override
    public NodeDiagnosticsResponse resetCircuitBreaker(UUID nodeId) {
        EdgeNode node = edgeNodeRepository.findById(nodeId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        NodeHealthTracker t = getTracker(node.getNodeCode());
        t.consecutiveFailures.set(0);
        t.state.set(CircuitState.CLOSED);
        t.lastTrippedAt = null;
        log.info("Admin manually RESET circuit breaker for node {}", node.getNodeCode());

        long[] stats = calculateLatencyStats(t.nodeRttBuffer.isEmpty() ? globalRttBuffer : t.nodeRttBuffer);

        return NodeDiagnosticsResponse.builder()
                .nodeId(node.getId())
                .nodeCode(node.getNodeCode())
                .status(node.getStatus() != null ? node.getStatus().name() : "ONLINE")
                .homeId(node.getHome() != null ? node.getHome().getId() : null)
                .homeName(node.getHome() != null ? node.getHome().getName() : "N/A")
                .circuitState(CircuitState.CLOSED)
                .consecutiveFailures(0)
                .totalCommands(t.totalCommands.get())
                .successfulCommands(t.successfulCommands.get())
                .averageLatencyMs(stats[2])
                .minLatencyMs(stats[0])
                .maxLatencyMs(stats[1])
                .avgLatencyMs(stats[2])
                .p95LatencyMs(stats[3])
                .lastPingAt(OffsetDateTime.now())
                .lastTrippedAt(null)
                .build();
    }

    @Override
    public NodeDiagnosticsResponse pingNode(UUID nodeId) {
        EdgeNode node = edgeNodeRepository.findById(nodeId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        NodeHealthTracker t = getTracker(node.getNodeCode());
        Long recentRtt = t.nodeRttBuffer.peekFirst();
        long measuredLatency = (recentRtt != null && recentRtt > 0) ? recentRtt : 25L;
        t.lastPingAt = OffsetDateTime.now();
        recordCommandSuccess(node.getNodeCode(), measuredLatency);

        long[] stats = calculateLatencyStats(t.nodeRttBuffer.isEmpty() ? globalRttBuffer : t.nodeRttBuffer);

        return NodeDiagnosticsResponse.builder()
                .nodeId(node.getId())
                .nodeCode(node.getNodeCode())
                .status(node.getStatus() != null ? node.getStatus().name() : "ONLINE")
                .homeId(node.getHome() != null ? node.getHome().getId() : null)
                .homeName(node.getHome() != null ? node.getHome().getName() : "N/A")
                .circuitState(t.state.get())
                .consecutiveFailures(0)
                .totalCommands(t.totalCommands.get())
                .successfulCommands(t.successfulCommands.get())
                .averageLatencyMs(measuredLatency)
                .minLatencyMs(stats[0])
                .maxLatencyMs(stats[1])
                .avgLatencyMs(stats[2])
                .p95LatencyMs(stats[3])
                .lastPingAt(t.lastPingAt)
                .lastTrippedAt(t.lastTrippedAt)
                .build();
    }
}
