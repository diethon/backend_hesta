package com.hesta.backend.diagnostics;

import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.EdgeNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Value("${mqtt.broker.url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${mqtt.circuit-breaker.enabled:true}")
    private boolean circuitBreakerEnabled = true;

    @Value("${mqtt.circuit-breaker.failure-threshold:3}")
    private int failureThreshold = 3;

    @Value("${mqtt.circuit-breaker.cooldown-seconds:60}")
    private long cooldownSeconds = 60;

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
        final java.util.concurrent.atomic.AtomicBoolean halfOpenTrialInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
        volatile OffsetDateTime lastTrippedAt = null;
        volatile OffsetDateTime lastPingAt = null;
        final java.util.concurrent.atomic.AtomicBoolean isOnline = new java.util.concurrent.atomic.AtomicBoolean(false);
    }

    private final long serviceStartupTimestampMs = System.currentTimeMillis();

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
        if (!circuitBreakerEnabled) {
            return true;
        }
        if (nodeCode == null) return true;
        NodeHealthTracker tracker = getTracker(nodeCode);
        CircuitState currentState = tracker.state.get();

        if (currentState == CircuitState.CLOSED) {
            return true;
        }

        if (currentState == CircuitState.OPEN) {
            if (tracker.lastTrippedAt != null &&
                    Duration.between(tracker.lastTrippedAt, OffsetDateTime.now()).getSeconds() > cooldownSeconds) {
                if (tracker.state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    tracker.halfOpenTrialInFlight.set(false);
                    log.info("Circuit breaker for node {} transitioned to HALF_OPEN (trial execution allowed)", nodeCode);
                }
            } else {
                return false;
            }
        }

        if (tracker.state.get() == CircuitState.HALF_OPEN) {
            // Prevent thundering herd: allow only one trial execution in flight
            return tracker.halfOpenTrialInFlight.compareAndSet(false, true);
        }

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
            tracker.halfOpenTrialInFlight.set(false);
            log.info("Circuit breaker for node {} restored to CLOSED (Healthy)", nodeCode);
        } else {
            tracker.halfOpenTrialInFlight.set(false);
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

        tracker.halfOpenTrialInFlight.set(false);
        if (fails >= failureThreshold || tracker.state.get() == CircuitState.HALF_OPEN) {
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
        if (node == null) {
            try {
                node = edgeNodeRepository.findById(UUID.fromString(nodeCode)).orElse(null);
                if (node != null) {
                    nodeCode = node.getNodeCode();
                }
            } catch (Exception ignored) {}
        }
        NodeHealthTracker tracker = getTracker(nodeCode);
        tracker.state.set(CircuitState.OPEN);
        tracker.halfOpenTrialInFlight.set(false);
        tracker.lastTrippedAt = OffsetDateTime.now();
        tracker.consecutiveFailures.set(failureThreshold);
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
    public NodeDiagnosticsResponse tripCircuitBreaker(UUID nodeId) {
        EdgeNode node = edgeNodeRepository.findById(nodeId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        return tripCircuitBreaker(node.getNodeCode());
    }

    @Override
    public void recordPacket(String topic, String direction, int qos, String payload) {
        String summary = payload;
        if (summary != null && summary.length() > 200) {
            summary = summary.substring(0, 197) + "...";
        }
        String normalizedDir = "INBOUND";
        if (direction != null) {
            if ("OUT".equalsIgnoreCase(direction) || "OUTBOUND".equalsIgnoreCase(direction)) {
                normalizedDir = "OUTBOUND";
            } else if ("IN".equalsIgnoreCase(direction) || "INBOUND".equalsIgnoreCase(direction)) {
                normalizedDir = "INBOUND";
            } else {
                normalizedDir = direction.toUpperCase();
            }
        }
        MqttPacketDto packet = MqttPacketDto.builder()
                .id(UUID.randomUUID().toString())
                .timestamp(OffsetDateTime.now())
                .topic(topic)
                .direction(normalizedDir)
                .qos(qos)
                .payloadSummary(summary)
                .build();
        snifferBuffer.addFirst(packet);
        while (snifferBuffer.size() > SNIFFER_BUFFER_SIZE) {
            snifferBuffer.pollLast();
        }

        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend("/topic/mqtt-sniffer", packet);
            } catch (Exception e) {
                log.debug("Failed to broadcast sniffer packet via websocket: {}", e.getMessage());
            }
        }

        // PHO-038 Dead Man's Snitch: cập nhật heartbeat khi nhận bản tin từ node (Fix Key Mismatch giữa nodeCode và UUID)
        if (topic != null && topic.startsWith("hesta/nodes/")) {
            String[] parts = topic.split("/");
            if (parts.length >= 3) {
                String nodeIdent = parts[2];
                // Bỏ qua topic broadcast hoặc unknown để không query DB vô ích
                if ("broadcast".equalsIgnoreCase(nodeIdent) || "unknown".equalsIgnoreCase(nodeIdent)) {
                    return;
                }

                NodeHealthTracker tracker = trackers.get(nodeIdent);
                EdgeNode matchedNode = null;
                if (tracker == null) {
                    matchedNode = edgeNodeRepository.findByNodeCode(nodeIdent).orElse(null);
                    if (matchedNode == null) {
                        try {
                            matchedNode = edgeNodeRepository.findById(UUID.fromString(nodeIdent)).orElse(null);
                        } catch (Exception ignored) {}
                    }
                    if (matchedNode != null) {
                        tracker = trackers.computeIfAbsent(matchedNode.getNodeCode(), k -> new NodeHealthTracker());
                        tracker.isOnline.set(matchedNode.getStatus() == EdgeNodeStatus.ONLINE);
                        trackers.putIfAbsent(nodeIdent, tracker);
                        trackers.putIfAbsent(matchedNode.getId().toString(), tracker);
                    }
                }
                if (tracker != null) {
                    tracker.lastPingAt = OffsetDateTime.now();

                    // Auto-recovery: Nếu node đang OFFLINE trong DB thì tự động khôi phục sang ONLINE khi nhận tin
                    if (tracker.isOnline.compareAndSet(false, true)) {
                        if (matchedNode == null) {
                            matchedNode = edgeNodeRepository.findByNodeCode(nodeIdent).orElse(null);
                            if (matchedNode == null) {
                                try {
                                    matchedNode = edgeNodeRepository.findById(UUID.fromString(nodeIdent)).orElse(null);
                                } catch (Exception ignored) {}
                            }
                        }
                        if (matchedNode != null && matchedNode.getStatus() != EdgeNodeStatus.ONLINE) {
                            matchedNode.setStatus(EdgeNodeStatus.ONLINE);
                            edgeNodeRepository.save(matchedNode);
                            log.info("[PHO-038 Dead Man's Snitch] Node {} revived to ONLINE via received packet on topic {}", 
                                    matchedNode.getNodeCode(), topic);
                        }
                    }
                }
            }
        }
    }

    /**
     * PHO-038: Dead Man's Snitch Node Heartbeat Monitor
     * Quét định kỳ mỗi 30s. Nếu node quá 60s không nhận được bất kỳ bản tin nào -> Chuyển trạng thái sang OFFLINE.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 30000)
    public void evaluateDeadMansSnitch() {
        OffsetDateTime now = OffsetDateTime.now();
        List<EdgeNode> allNodes = edgeNodeRepository.findAll();
        long uptimeMs = System.currentTimeMillis() - serviceStartupTimestampMs;

        for (EdgeNode node : allNodes) {
            NodeHealthTracker t = trackers.get(node.getNodeCode());
            if (t == null) {
                t = trackers.computeIfAbsent(node.getNodeCode(), k -> new NodeHealthTracker());
                t.isOnline.set(node.getStatus() == EdgeNodeStatus.ONLINE);
                trackers.putIfAbsent(node.getId().toString(), t);
            }

            if (t.lastPingAt != null) {
                long elapsedSeconds = Duration.between(t.lastPingAt, now).getSeconds();
                if (elapsedSeconds > 60 && node.getStatus() == EdgeNodeStatus.ONLINE) {
                    node.setStatus(EdgeNodeStatus.OFFLINE);
                    edgeNodeRepository.save(node);
                    t.isOnline.set(false);
                    log.warn("[PHO-038 Dead Man's Snitch] Node {} timed out (>60s without heartbeat, last seen {}s ago). Status marked OFFLINE.",
                            node.getNodeCode(), elapsedSeconds);
                }
            } else if (uptimeMs > 60000 && node.getStatus() == EdgeNodeStatus.ONLINE) {
                // Node trong DB ghi ONLINE nhưng qua 60s kể từ server start vẫn không gửi 1 ping nào -> đánh OFFLINE
                node.setStatus(EdgeNodeStatus.OFFLINE);
                edgeNodeRepository.save(node);
                t.isOnline.set(false);
                log.warn("[PHO-038 Dead Man's Snitch] Node {} has had no heartbeat since server boot (>60s). Marking OFFLINE.",
                        node.getNodeCode());
            }
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
    @Transactional(readOnly = true)
    public List<NodeDiagnosticsResponse> getNodeDiagnostics() {
        List<EdgeNode> allNodes = edgeNodeRepository.findAllWithHome();
        if (allNodes == null || allNodes.isEmpty()) {
            allNodes = edgeNodeRepository.findAll();
        }
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
    @Transactional
    public NodeDiagnosticsResponse resetCircuitBreaker(UUID nodeId) {
        EdgeNode node = edgeNodeRepository.findByIdWithHome(nodeId)
                .or(() -> edgeNodeRepository.findById(nodeId))
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        NodeHealthTracker t = getTracker(node.getNodeCode());
        t.consecutiveFailures.set(0);
        t.state.set(CircuitState.CLOSED);
        t.halfOpenTrialInFlight.set(false);
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
                .lastPingAt(t.lastPingAt)
                .lastTrippedAt(null)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public NodeDiagnosticsResponse pingNode(UUID nodeId) {
        EdgeNode node = edgeNodeRepository.findByIdWithHome(nodeId)
                .or(() -> edgeNodeRepository.findById(nodeId))
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
