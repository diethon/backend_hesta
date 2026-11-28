package com.hesta.backend.diagnostics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.EdgeNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.integration.mqtt.event.MqttConnectionFailedEvent;
import org.springframework.integration.mqtt.event.MqttSubscribedEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private com.hesta.backend.repository.DeviceRepository deviceRepository;

    @Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired(required = false)
    private com.hesta.backend.realtime.publisher.RealtimeEventPublisher realtimeEventPublisher;

    @Autowired(required = false)
    private com.hesta.backend.mapper.TwinSnapshotMapper twinSnapshotMapper;

    @Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Autowired(required = false)
    private com.hesta.backend.config.mqtt.MqttGateway mqttGateway;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private com.hesta.backend.cache.HestaCacheService cacheService;

    @Value("${mqtt.broker.url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${mqtt.topic.prefix:hesta/nodes}")
    private String topicPrefix = "hesta/nodes";

    @Value("${mqtt.ping.timeout-ms:3000}")
    private long pingTimeoutMs = 3000L;

    @Value("${mqtt.circuit-breaker.enabled:true}")
    private boolean circuitBreakerEnabled = true;

    @Value("${mqtt.circuit-breaker.failure-threshold:3}")
    private int failureThreshold = 3;

    @Value("${mqtt.circuit-breaker.cooldown-seconds:60}")
    private long cooldownSeconds = 60;

    private static final int RTT_BUFFER_SIZE = 100;
    private static final int SNIFFER_BUFFER_SIZE = 50;
    private static final int NODE_SNIFFER_BUFFER_SIZE = 100;

    private final AtomicBoolean brokerConnected = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, NodeHealthTracker> trackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PingRequest> pendingPings = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> globalRttBuffer = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<MqttPacketDto> snifferBuffer = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private static class PingRequest {
        final String nodeCode;
        final UUID nodeId;
        final long startNanos;
        final CompletableFuture<Long> future;

        PingRequest(String nodeCode, UUID nodeId, long startNanos, CompletableFuture<Long> future) {
            this.nodeCode = nodeCode;
            this.nodeId = nodeId;
            this.startNanos = startNanos;
            this.future = future;
        }
    }

    @EventListener(MqttSubscribedEvent.class)
    public void onMqttSubscribed(MqttSubscribedEvent event) {
        brokerConnected.set(true);
        log.info("[Diagnostics] MQTT Broker connection ACTIVE");
    }

    @EventListener(MqttConnectionFailedEvent.class)
    public void onMqttConnectionFailed(MqttConnectionFailedEvent event) {
        brokerConnected.set(false);
        log.warn("[Diagnostics] MQTT Broker connection LOST: {}",
                event.getCause() != null ? event.getCause().getMessage() : "Disconnected");
    }

    @Override
    public boolean isBrokerConnected() {
        return brokerConnected.get();
    }

    private ObjectMapper getObjectMapper() {
        return objectMapper != null ? objectMapper : new ObjectMapper();
    }

    private static class NodeHealthTracker {
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        final AtomicInteger totalCommands = new AtomicInteger(0);
        final AtomicInteger successfulCommands = new AtomicInteger(0);
        final AtomicLong totalLatencyMs = new AtomicLong(0);
        final java.util.concurrent.ConcurrentLinkedDeque<Long> nodeRttBuffer = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final java.util.concurrent.ConcurrentLinkedDeque<MqttPacketDto> nodePacketBuffer = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
        final java.util.concurrent.atomic.AtomicBoolean halfOpenTrialInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
        volatile OffsetDateTime lastTrippedAt = null;
        volatile OffsetDateTime lastPingAt = null;
        final java.util.concurrent.atomic.AtomicBoolean isOnline = new java.util.concurrent.atomic.AtomicBoolean(false);
    }

    private final long serviceStartupTimestampMs = System.currentTimeMillis();

    private EdgeNode resolveEdgeNode(String nodeIdent) {
        if (nodeIdent == null || nodeIdent.isBlank()) {
            return null;
        }
        try {
            EdgeNode byId = edgeNodeRepository.findById(UUID.fromString(nodeIdent)).orElse(null);
            if (byId != null) {
                return byId;
            }
        } catch (IllegalArgumentException ignored) {}
        return edgeNodeRepository.findByNodeCode(nodeIdent).orElse(null);
    }

    private String canonicalNodeKey(EdgeNode node) {
        if (node != null && node.getNodeCode() != null && !node.getNodeCode().isBlank()) {
            return node.getNodeCode().trim();
        }
        if (node != null && node.getId() != null) {
            return node.getId().toString();
        }
        return "UNKNOWN";
    }

    private NodeHealthTracker getTracker(String nodeCode) {
        String key = (nodeCode == null || nodeCode.isBlank()) ? "UNKNOWN" : nodeCode;
        return trackers.computeIfAbsent(key, k -> new NodeHealthTracker());
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
    @Transactional(readOnly = true)
    public NodeDiagnosticsResponse tripCircuitBreaker(String nodeCode) {
        EdgeNode node = edgeNodeRepository.findByNodeCodeWithHome(nodeCode).orElse(null);
        if (node == null) {
            try {
                node = edgeNodeRepository.findByIdWithHome(UUID.fromString(nodeCode)).orElse(null);
            } catch (Exception ignored) {}
        }
        if (node != null) {
            nodeCode = canonicalNodeKey(node);
        }
        NodeHealthTracker tracker = getTracker(nodeCode);
        tracker.state.set(CircuitState.OPEN);
        tracker.halfOpenTrialInFlight.set(false);
        tracker.lastTrippedAt = OffsetDateTime.now();
        tracker.consecutiveFailures.set(failureThreshold);
        log.warn("Circuit breaker for node {} was manually TRIPPED to OPEN via diagnostics endpoint", nodeCode);

        long[] stats = calculateLatencyStats(tracker.nodeRttBuffer.isEmpty() ? globalRttBuffer : tracker.nodeRttBuffer);

        String homeName = "N/A";
        UUID homeId = null;
        if (node != null && node.getHome() != null) {
            try {
                homeId = node.getHome().getId();
                homeName = node.getHome().getName();
            } catch (Exception e) {
                log.debug("Lazy load home failed: {}", e.getMessage());
            }
        }

        NodeDeviceMeta meta = loadSingleNodeDeviceMeta(node);

        return NodeDiagnosticsResponse.builder()
                .nodeId(node != null ? node.getId() : null)
                .nodeCode(nodeCode)
                .status(node != null && node.getStatus() != null ? node.getStatus().name() : "OFFLINE")
                .firmwareVersion(node != null ? node.getFirmwareVersion() : null)
                .deviceType(node != null ? node.getDeviceType() : null)
                .attachedDeviceCount(meta.count())
                .attachedDeviceTypes(resolveAttachedDeviceTypes(node, meta))
                .homeId(homeId)
                .homeName(homeName)
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
    @Transactional(readOnly = true)
    public NodeDiagnosticsResponse tripCircuitBreaker(UUID nodeId) {
        EdgeNode node = edgeNodeRepository.findByIdWithHome(nodeId)
                .or(() -> edgeNodeRepository.findById(nodeId))
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        return tripCircuitBreaker(canonicalNodeKey(node));
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
        recordNodeSnifferPacket(topic, packet);

        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend("/topic/mqtt-sniffer", packet);
            } catch (Exception e) {
                log.debug("Failed to broadcast sniffer packet via websocket: {}", e.getMessage());
            }
        }

        if ("INBOUND".equals(normalizedDir)) {
            brokerConnected.set(true);
        }

        // PHO-038 Dead Man's Snitch: chỉ cập nhật heartbeat & revive ONLINE khi nhận bản tin INBOUND từ node
        if ("INBOUND".equals(normalizedDir) && topic != null && topic.startsWith("hesta/nodes/")) {
            String[] parts = topic.split("/");
            if (parts.length >= 3) {
                String nodeIdent = parts[2];
                // Bỏ qua topic broadcast hoặc unknown để không query DB vô ích
                if ("broadcast".equalsIgnoreCase(nodeIdent) || "unknown".equalsIgnoreCase(nodeIdent)) {
                    return;
                }
                boolean isInbound = "IN".equalsIgnoreCase(direction) || "INBOUND".equalsIgnoreCase(direction);
                if (!isInbound) {
                    return;
                }

                NodeHealthTracker tracker = trackers.get(nodeIdent);
                EdgeNode matchedNode = null;
                if (tracker == null) {
                    matchedNode = resolveEdgeNode(nodeIdent);
                    if (matchedNode != null) {
                        tracker = trackers.computeIfAbsent(matchedNode.getNodeCode(), k -> new NodeHealthTracker());
                        tracker.isOnline.set(matchedNode.getStatus() == EdgeNodeStatus.ONLINE);
                        trackers.putIfAbsent(nodeIdent, tracker);
                        trackers.putIfAbsent(matchedNode.getId().toString(), tracker);
                    }
                }
                if (tracker != null) {
                    if (matchedNode == null && topic.endsWith("/status") && parts.length == 4) {
                        matchedNode = resolveEdgeNode(nodeIdent);
                    }
                    boolean isSelfBridgedNodeStatus = topic.endsWith("/status") && parts.length == 4
                            && matchedNode != null
                            && nodeIdent.equalsIgnoreCase(matchedNode.getNodeCode())
                            && !nodeIdent.equalsIgnoreCase(matchedNode.getId().toString());
                    if (!isSelfBridgedNodeStatus) {
                        tracker.lastPingAt = OffsetDateTime.now();
                    }

                    // Auto-recovery: Nếu node đang OFFLINE trong DB thì tự động khôi phục sang ONLINE khi nhận tin
                    boolean becameOnline = !isSelfBridgedNodeStatus && tracker.isOnline.compareAndSet(false, true);
                    if (becameOnline || (!isSelfBridgedNodeStatus && matchedNode != null && matchedNode.getStatus() != EdgeNodeStatus.ONLINE)) {
                        if (matchedNode == null) {
                            matchedNode = resolveEdgeNode(nodeIdent);
                        }
                        if (matchedNode != null) {
                            trackers.putIfAbsent(matchedNode.getNodeCode(), tracker);
                            trackers.putIfAbsent(matchedNode.getId().toString(), tracker);
                            tracker.isOnline.set(true);
                            if (matchedNode.getStatus() != EdgeNodeStatus.ONLINE) {
                                matchedNode.setStatus(EdgeNodeStatus.ONLINE);
                                edgeNodeRepository.save(matchedNode);
                                log.info("[PHO-038 Dead Man's Snitch] Node {} revived to ONLINE via received packet on topic {}",
                                        matchedNode.getNodeCode(), topic);
                            }
                            cascadeNodeStatusToDevices(matchedNode, com.hesta.backend.enums.DeviceStatus.ONLINE);
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
            String nodeKey = canonicalNodeKey(node);
            NodeHealthTracker t = trackers.get(nodeKey);
            if (t == null) {
                t = trackers.computeIfAbsent(nodeKey, k -> new NodeHealthTracker());
                t.isOnline.set(node.getStatus() == EdgeNodeStatus.ONLINE);
                if (node.getId() != null) {
                    trackers.putIfAbsent(node.getId().toString(), t);
                }
            }

            if (t.lastPingAt != null) {
                long elapsedSeconds = Duration.between(t.lastPingAt, now).getSeconds();
                if (elapsedSeconds > 60 && node.getStatus() == EdgeNodeStatus.ONLINE) {
                    if (hasRecentChildActivity(node, now)) {
                        t.isOnline.set(true);
                        continue;
                    }
                    node.setStatus(EdgeNodeStatus.OFFLINE);
                    edgeNodeRepository.save(node);
                    t.isOnline.set(false);
                    cascadeNodeStatusToDevices(node, com.hesta.backend.enums.DeviceStatus.OFFLINE);
                    log.warn("[PHO-038 Dead Man's Snitch] Node {} timed out (>60s without heartbeat, last seen {}s ago). Status marked OFFLINE.",
                            nodeKey, elapsedSeconds);
                } else if (elapsedSeconds <= 60) {
                    if (node.getStatus() != EdgeNodeStatus.ONLINE) {
                        node.setStatus(EdgeNodeStatus.ONLINE);
                        edgeNodeRepository.save(node);
                    }
                    t.isOnline.set(true);
                    cascadeNodeStatusToDevices(node, com.hesta.backend.enums.DeviceStatus.ONLINE);
                    if (mqttGateway != null && !nodeKey.equals("UNKNOWN")
                            && (node.getId() == null || !nodeKey.equalsIgnoreCase(node.getId().toString()))) {
                        try {
                            mqttGateway.sendToMqtt("hesta/nodes/" + nodeKey + "/status", 0, "ONLINE");
                        } catch (Exception ex) {
                            log.debug("Failed to bridge status heartbeat for node {}: {}", nodeKey, ex.getMessage());
                        }
                    }
                }
            } else if (uptimeMs > 60000 && node.getStatus() == EdgeNodeStatus.ONLINE) {
                if (hasRecentChildActivity(node, now)) {
                    t.isOnline.set(true);
                    continue;
                }
                // Node trong DB ghi ONLINE nhưng qua 60s kể từ server start vẫn không gửi 1 ping nào -> đánh OFFLINE
                node.setStatus(EdgeNodeStatus.OFFLINE);
                edgeNodeRepository.save(node);
                t.isOnline.set(false);
                cascadeNodeStatusToDevices(node, com.hesta.backend.enums.DeviceStatus.OFFLINE);
                log.warn("[PHO-038 Dead Man's Snitch] Node {} has had no heartbeat since server boot (>60s). Marking OFFLINE.",
                        nodeKey);
            } else if (node.getStatus() == EdgeNodeStatus.OFFLINE) {
                // Node đang OFFLINE: Đảm bảo toàn bộ thiết bị phụ thuộc vào Node cũng phải là OFFLINE
                cascadeNodeStatusToDevices(node, com.hesta.backend.enums.DeviceStatus.OFFLINE);
            }
        }
    }

    private boolean hasRecentChildActivity(EdgeNode node, OffsetDateTime now) {
        if (deviceRepository == null || node == null) return false;
        try {
            for (com.hesta.backend.entity.Device dev : deviceRepository.findByNodeIdOrNodeCode(node.getId(), node.getNodeCode())) {
                if (dev.getStatus() == com.hesta.backend.enums.DeviceStatus.ONLINE
                        && dev.getLastSeen() != null
                        && Math.abs(Duration.between(dev.getLastSeen(), now).getSeconds()) <= 90) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void cascadeNodeStatusToDevices(EdgeNode node, com.hesta.backend.enums.DeviceStatus newStatus) {
        if (deviceRepository == null || node == null) return;
        try {
            Runnable cascade = () -> {
                List<com.hesta.backend.entity.Device> devices = deviceRepository.findByNodeIdOrNodeCode(node.getId(), node.getNodeCode());
                for (com.hesta.backend.entity.Device dev : devices) {
                    if (dev.getStatus() != newStatus) {
                        dev.setStatus(newStatus);
                        dev.setLastSeen(OffsetDateTime.now());
                        deviceRepository.save(dev);
                        log.info("Cascaded status {} to device {} ({}) for node {}", newStatus, dev.getName(), dev.getId(), node.getNodeCode());
                        publishDeviceStateChanged(dev);
                    }
                }
            };
            // The watchdog runs on a scheduler thread: without a transaction the lazy room/home of each device
            // cannot load, the broadcast fails and the loop stops after the first device.
            if (transactionManager != null) {
                org.springframework.transaction.support.TransactionTemplate tx =
                        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
                tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
                tx.executeWithoutResult(status -> cascade.run());
            } else {
                cascade.run();
            }
        } catch (Exception ex) {
            log.error("Failed to cascade status {} to devices of node {}: {}", newStatus, node.getNodeCode(), ex.getMessage());
        }
    }

    private void publishDeviceStateChanged(com.hesta.backend.entity.Device dev) {
        UUID homeId = dev.getHome() != null ? dev.getHome().getId() : null;
        if (homeId == null) return;

        // One broadcast per change: inside a transaction the AFTER_COMMIT listener sends it, otherwise we do.
        boolean inTransaction = org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive();
        if (inTransaction && applicationEventPublisher != null) {
            try {
                var snapshot = twinSnapshotMapper != null ? twinSnapshotMapper.device(dev) : null;
                if (snapshot != null) {
                    applicationEventPublisher.publishEvent(new com.hesta.backend.dto.command.DeviceStateChangedEvent(homeId, snapshot));
                }
            } catch (Exception ex) {
                log.warn("Failed to publish DeviceStateChangedEvent for device {}: {}", dev.getId(), ex.getMessage());
            }
            return;
        }

        if (cacheService != null) {
            String homePrefix = homeId + ":";
            cacheService.evictByPrefix("devices:home", homePrefix);
            cacheService.evictByPrefix("home:activity", homePrefix);
            cacheService.evictByPrefix("energy:home", homePrefix);
            if (dev.getId() != null) {
                String devPrefix = dev.getId() + ":";
                cacheService.evictByPrefix("energy:device", devPrefix);
                cacheService.evictByPrefix("device:uptime", devPrefix);
                cacheService.evict("device:history", dev.getId().toString());
            }
        }

        if (realtimeEventPublisher != null) {
            try {
                var snapshot = twinSnapshotMapper != null ? twinSnapshotMapper.device(dev) : null;
                if (snapshot != null) {
                    realtimeEventPublisher.publish(com.hesta.backend.realtime.model.RealtimeEvent.create(
                            com.hesta.backend.realtime.model.RealtimeEventType.DEVICE_STATE_CHANGED,
                            homeId,
                            dev.getId(),
                            snapshot
                    ));
                }
            } catch (Exception ex) {
                log.warn("Failed to publish realtime websocket event for device {}: {}", dev.getId(), ex.getMessage());
            }
        }
    }

    private void recordNodeSnifferPacket(String topic, MqttPacketDto packet) {
        if (topic == null || !topic.startsWith("hesta/nodes/")) {
            return;
        }
        String[] parts = topic.split("/");
        if (parts.length < 3) {
            return;
        }
        String nodeIdent = parts[2];
        if (nodeIdent == null || nodeIdent.isBlank()
                || "broadcast".equalsIgnoreCase(nodeIdent)
                || "unknown".equalsIgnoreCase(nodeIdent)) {
            return;
        }
        NodeHealthTracker tracker = trackers.get(nodeIdent);
        if (tracker == null) {
            EdgeNode matched = resolveEdgeNode(nodeIdent);
            if (matched != null && matched.getNodeCode() != null && !matched.getNodeCode().isBlank()) {
                tracker = trackers.computeIfAbsent(matched.getNodeCode(), k -> {
                    NodeHealthTracker created = new NodeHealthTracker();
                    created.isOnline.set(matched.getStatus() == EdgeNodeStatus.ONLINE);
                    return created;
                });
                trackers.putIfAbsent(nodeIdent, tracker);
                if (matched.getId() != null) {
                    trackers.putIfAbsent(matched.getId().toString(), tracker);
                }
            } else {
                tracker = getTracker(nodeIdent);
            }
        }
        tracker.nodePacketBuffer.addFirst(packet);
        while (tracker.nodePacketBuffer.size() > NODE_SNIFFER_BUFFER_SIZE) {
            tracker.nodePacketBuffer.pollLast();
        }
    }

    @Override
    public java.util.List<MqttPacketDto> getSnifferPackets() {
        return new java.util.ArrayList<>(snifferBuffer);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<MqttPacketDto> getSnifferPackets(String nodeCode, UUID homeId) {
        boolean hasNode = nodeCode != null && !nodeCode.isBlank();
        if (!hasNode && homeId == null) {
            return getSnifferPackets();
        }

        java.util.Set<String> targetIdents = new java.util.LinkedHashSet<>();
        if (hasNode) {
            String trimmed = nodeCode.trim();
            targetIdents.add(trimmed);
            EdgeNode resolved = resolveEdgeNode(trimmed);
            if (resolved != null) {
                if (resolved.getNodeCode() != null && !resolved.getNodeCode().isBlank()) {
                    targetIdents.add(resolved.getNodeCode());
                }
                if (resolved.getId() != null) {
                    targetIdents.add(resolved.getId().toString());
                }
            }
        } else {
            List<EdgeNode> homeNodes = edgeNodeRepository.findByHome_Id(homeId);
            if (homeNodes != null) {
                for (EdgeNode n : homeNodes) {
                    if (n.getNodeCode() != null && !n.getNodeCode().isBlank()) {
                        targetIdents.add(n.getNodeCode());
                    }
                    if (n.getId() != null) {
                        targetIdents.add(n.getId().toString());
                    }
                }
            }
        }

        if (targetIdents.isEmpty()) {
            return java.util.List.of();
        }

        java.util.Map<String, MqttPacketDto> merged = new java.util.LinkedHashMap<>();
        for (String ident : targetIdents) {
            NodeHealthTracker tracker = trackers.get(ident);
            if (tracker != null) {
                for (MqttPacketDto pkt : tracker.nodePacketBuffer) {
                    if (pkt != null && pkt.getId() != null) {
                        merged.putIfAbsent(pkt.getId(), pkt);
                    }
                }
            }
        }

        for (MqttPacketDto pkt : snifferBuffer) {
            if (pkt == null || pkt.getTopic() == null) continue;
            String t = pkt.getTopic();
            for (String ident : targetIdents) {
                if (t.startsWith("hesta/nodes/" + ident + "/") || t.equals("hesta/nodes/" + ident)) {
                    merged.putIfAbsent(pkt.getId() != null ? pkt.getId() : (t + pkt.getTimestamp()), pkt);
                    break;
                }
            }
        }

        return merged.values().stream()
                .sorted((a, b) -> {
                    if (a.getTimestamp() == null && b.getTimestamp() == null) return 0;
                    if (a.getTimestamp() == null) return 1;
                    if (b.getTimestamp() == null) return -1;
                    return b.getTimestamp().compareTo(a.getTimestamp());
                })
                .limit(NODE_SNIFFER_BUFFER_SIZE)
                .collect(Collectors.toList());
    }

    private record NodeDeviceMeta(int count, java.util.Set<String> types) {}

    private NodeDeviceMeta loadSingleNodeDeviceMeta(EdgeNode node) {
        if (deviceRepository == null || node == null || node.getId() == null) {
            return new NodeDeviceMeta(0, java.util.Set.of());
        }
        try {
            List<com.hesta.backend.entity.Device> list = deviceRepository.findByNodeIdOrNodeCode(node.getId(), node.getNodeCode());
            if (list == null || list.isEmpty()) {
                return new NodeDeviceMeta(0, java.util.Set.of());
            }
            int activeCount = 0;
            java.util.Set<String> types = new java.util.LinkedHashSet<>();
            for (com.hesta.backend.entity.Device d : list) {
                if (d.isDeleted()) continue;
                activeCount++;
                if (d.getDeviceType() != null && !d.getDeviceType().isBlank()) {
                    types.add(d.getDeviceType().trim().toUpperCase(java.util.Locale.ROOT));
                }
            }
            return new NodeDeviceMeta(activeCount, types);
        } catch (Exception ex) {
            return new NodeDeviceMeta(0, java.util.Set.of());
        }
    }

    private java.util.Map<UUID, NodeDeviceMeta> loadAttachedDeviceMetadata() {
        java.util.Map<UUID, NodeDeviceMeta> map = new java.util.HashMap<>();
        if (deviceRepository == null) {
            return map;
        }
        try {
            List<Object[]> rows = deviceRepository.countAttachedDevicesByNodeAndType();
            if (rows == null) return map;
            for (Object[] row : rows) {
                if (row == null || row.length < 3 || !(row[0] instanceof UUID nodeId)) continue;
                String devType = row[1] != null ? row[1].toString().trim().toUpperCase(java.util.Locale.ROOT) : "";
                int cnt = row[2] instanceof Number num ? num.intValue() : 0;
                NodeDeviceMeta existing = map.get(nodeId);
                java.util.Set<String> types = existing != null
                        ? new java.util.LinkedHashSet<>(existing.types())
                        : new java.util.LinkedHashSet<>();
                if (!devType.isBlank()) {
                    types.add(devType);
                }
                int nextCount = (existing != null ? existing.count() : 0) + cnt;
                map.put(nodeId, new NodeDeviceMeta(nextCount, types));
            }
        } catch (Exception ex) {
            log.debug("Failed to load attached device metadata: {}", ex.getMessage());
        }
        return map;
    }

    private java.util.List<String> resolveAttachedDeviceTypes(EdgeNode node, NodeDeviceMeta dbMeta) {
        if (dbMeta != null && dbMeta.types() != null && !dbMeta.types().isEmpty()) {
            return new java.util.ArrayList<>(dbMeta.types());
        }
        java.util.Set<String> types = new java.util.LinkedHashSet<>();
        if (node != null && node.getDeviceType() != null && !node.getDeviceType().isBlank()) {
            types.add(node.getDeviceType().trim().toUpperCase(java.util.Locale.ROOT));
        }
        return new java.util.ArrayList<>(types);
    }

    @Override
    @Transactional(readOnly = true)
    public DiagnosticsSummaryResponse getSummary() {
        List<EdgeNode> allNodes = edgeNodeRepository.findAllWithHome();
        if (allNodes == null || allNodes.isEmpty()) {
            allNodes = edgeNodeRepository.findAll();
        }
        int total = allNodes.size();
        int online = (int) allNodes.stream().filter(n -> n.getStatus() == EdgeNodeStatus.ONLINE).count();
        int offline = total - online;

        java.util.Set<UUID> homeIds = new java.util.HashSet<>();
        int quarantined = 0;
        int totalCmds = 0;
        int successCmds = 0;

        for (EdgeNode node : allNodes) {
            try {
                if (node.getHome() != null && node.getHome().getId() != null) {
                    homeIds.add(node.getHome().getId());
                }
            } catch (Exception ignored) {}
            NodeHealthTracker t = trackers.get(canonicalNodeKey(node));
            if (t != null) {
                if (t.state.get() == CircuitState.OPEN) quarantined++;
                totalCmds += t.totalCommands.get();
                successCmds += t.successfulCommands.get();
            }
        }

        double reliability = totalCmds > 0 ? ((double) successCmds / totalCmds) * 100.0 : 100.0;
        long[] stats = calculateLatencyStats(globalRttBuffer);

        return DiagnosticsSummaryResponse.builder()
                .brokerConnected(brokerConnected.get())
                .brokerUrl(brokerUrl)
                .totalHomes(homeIds.size())
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
        java.util.Map<UUID, NodeDeviceMeta> deviceMetaMap = loadAttachedDeviceMetadata();
        return allNodes.stream().map(node -> {
            String nodeKey = canonicalNodeKey(node);
            NodeHealthTracker t = getTracker(nodeKey);
            int total = t.totalCommands.get();
            int succ = t.successfulCommands.get();
            long[] stats = calculateLatencyStats(t.nodeRttBuffer.isEmpty() ? globalRttBuffer : t.nodeRttBuffer);
            NodeDeviceMeta meta = node.getId() != null ? deviceMetaMap.get(node.getId()) : null;

            return NodeDiagnosticsResponse.builder()
                    .nodeId(node.getId())
                    .nodeCode(node.getNodeCode())
                    .status(node.getStatus() != null ? node.getStatus().name() : "OFFLINE")
                    .firmwareVersion(node.getFirmwareVersion())
                    .deviceType(node.getDeviceType())
                    .attachedDeviceCount(meta != null ? meta.count() : 0)
                    .attachedDeviceTypes(resolveAttachedDeviceTypes(node, meta))
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

        String nodeKey = canonicalNodeKey(node);
        NodeHealthTracker t = getTracker(nodeKey);
        t.consecutiveFailures.set(0);
        t.state.set(CircuitState.CLOSED);
        t.halfOpenTrialInFlight.set(false);
        t.lastTrippedAt = null;
        log.info("Admin manually RESET circuit breaker for node {}", nodeKey);

        long[] stats = calculateLatencyStats(t.nodeRttBuffer.isEmpty() ? globalRttBuffer : t.nodeRttBuffer);
        NodeDeviceMeta meta = loadSingleNodeDeviceMeta(node);

        return NodeDiagnosticsResponse.builder()
                .nodeId(node.getId())
                .nodeCode(node.getNodeCode())
                .status(node.getStatus() != null ? node.getStatus().name() : "ONLINE")
                .firmwareVersion(node.getFirmwareVersion())
                .deviceType(node.getDeviceType())
                .attachedDeviceCount(meta.count())
                .attachedDeviceTypes(resolveAttachedDeviceTypes(node, meta))
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
    public NodeDiagnosticsResponse pingNode(UUID nodeId) {
        EdgeNode node = edgeNodeRepository.findByIdWithHome(nodeId)
                .or(() -> edgeNodeRepository.findById(nodeId))
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        String nodeCode = canonicalNodeKey(node);
        UUID homeId = null;
        String homeName = "N/A";
        if (node.getHome() != null) {
            try {
                homeId = node.getHome().getId();
                homeName = node.getHome().getName();
            } catch (Exception e) {
                log.debug("Lazy load home failed during pingNode: {}", e.getMessage());
            }
        }

        if (mqttGateway == null) {
            throw new AppException(ErrorCode.GATEWAY_DISCONNECTED);
        }

        String pingId = UUID.randomUUID().toString();
        String prefix = (topicPrefix != null && !topicPrefix.isBlank()) ? topicPrefix : "hesta/nodes";
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        NodeHealthTracker canonicalTracker = getTracker(nodeCode);
        trackers.putIfAbsent(nodeId.toString(), canonicalTracker);

        java.util.List<String> pingIdents = new java.util.ArrayList<>(2);
        pingIdents.add(nodeCode);
        if (!nodeCode.equalsIgnoreCase(nodeId.toString())) {
            pingIdents.add(nodeId.toString());
        }

        String payload;
        try {
            payload = getObjectMapper().writeValueAsString(Map.of(
                    "pingId", pingId,
                    "nodeCode", nodeCode,
                    "nodeId", nodeId.toString(),
                    "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            throw new AppException(ErrorCode.MQTT_PUBLISH_FAILED);
        }

        CompletableFuture<Long> future = new CompletableFuture<>();
        PingRequest pingReq = new PingRequest(nodeCode, nodeId, System.nanoTime(), future);
        pendingPings.put(pingId, pingReq);

        long measuredLatency;
        try {
            try {
                for (String ident : pingIdents) {
                    String pingTopic = prefix + "/" + ident + "/ping";
                    mqttGateway.sendToMqtt(pingTopic, 1, payload);
                    recordPacket(pingTopic, "OUT", 1, payload);
                }
            } catch (Exception e) {
                recordCommandFailure(nodeCode);
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("Client is not connected") || msg.contains("Connection lost") || msg.contains("mqttOutbound")) {
                    brokerConnected.set(false);
                    throw new AppException(ErrorCode.GATEWAY_DISCONNECTED);
                }
                throw new AppException(ErrorCode.MQTT_PUBLISH_FAILED);
            }

            long timeout = pingTimeoutMs > 0 ? pingTimeoutMs : 3000L;
            measuredLatency = future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            recordCommandFailure(nodeCode);
            throw new AppException(ErrorCode.PING_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordCommandFailure(nodeCode);
            throw new AppException(ErrorCode.PING_TIMEOUT);
        } catch (ExecutionException e) {
            recordCommandFailure(nodeCode);
            throw new AppException(ErrorCode.MQTT_PUBLISH_FAILED);
        } finally {
            pendingPings.remove(pingId);
        }

        EdgeNode refreshed = edgeNodeRepository.findById(nodeId).orElse(node);
        NodeHealthTracker t = getTracker(nodeCode);
        long[] stats = calculateLatencyStats(t.nodeRttBuffer.isEmpty() ? globalRttBuffer : t.nodeRttBuffer);
        NodeDeviceMeta meta = loadSingleNodeDeviceMeta(refreshed);

        return NodeDiagnosticsResponse.builder()
                .nodeId(refreshed.getId())
                .nodeCode(nodeCode)
                .status(refreshed.getStatus() != null ? refreshed.getStatus().name() : "ONLINE")
                .firmwareVersion(refreshed.getFirmwareVersion())
                .deviceType(refreshed.getDeviceType())
                .attachedDeviceCount(meta.count())
                .attachedDeviceTypes(resolveAttachedDeviceTypes(refreshed, meta))
                .homeId(homeId)
                .homeName(homeName)
                .circuitState(t.state.get())
                .consecutiveFailures(t.consecutiveFailures.get())
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

    @Override
    public void handlePong(String nodeCode, String payload) {
        if ((nodeCode == null || nodeCode.isBlank()) && (payload == null || payload.isBlank())) {
            return;
        }
        String pingId = null;
        String payloadNodeCode = null;
        String payloadNodeId = null;
        if (payload != null && !payload.isBlank()) {
            try {
                Map<String, Object> data = getObjectMapper().readValue(payload, new TypeReference<Map<String, Object>>() {});
                Object rawId = data.get("pingId");
                if (rawId != null && !rawId.toString().isBlank()) {
                    pingId = rawId.toString().trim();
                }
                Object rawCode = data.get("nodeCode");
                if (rawCode != null && !rawCode.toString().isBlank()) {
                    payloadNodeCode = rawCode.toString().trim();
                }
                Object rawNodeId = data.get("nodeId");
                if (rawNodeId != null && !rawNodeId.toString().isBlank()) {
                    payloadNodeId = rawNodeId.toString().trim();
                }
            } catch (Exception e) {
                log.debug("Could not parse pong JSON payload from node {}: {}", nodeCode, e.getMessage());
            }
        }

        PingRequest matched = null;
        if (pingId != null) {
            matched = pendingPings.remove(pingId);
        }
        if (matched == null) {
            for (Map.Entry<String, PingRequest> entry : pendingPings.entrySet()) {
                PingRequest req = entry.getValue();
                boolean idMatch = matchesPingTarget(req, nodeCode)
                        || matchesPingTarget(req, payloadNodeCode)
                        || matchesPingTarget(req, payloadNodeId);
                if (idMatch && pendingPings.remove(entry.getKey(), req)) {
                    matched = req;
                    break;
                }
            }
        }

        if (matched != null) {
            long rttMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - matched.startNanos));
            NodeHealthTracker t = getTracker(matched.nodeCode);
            t.lastPingAt = OffsetDateTime.now();
            recordCommandSuccess(matched.nodeCode, rttMs);
            matched.future.complete(rttMs);
            log.info("[Diagnostics] Real MQTT PONG received from node {} in {} ms", matched.nodeCode, rttMs);
        } else {
            log.debug("[Diagnostics] Unmatched PONG received from node {} (already timed out or unsolicited)", nodeCode);
        }
    }

    private boolean matchesPingTarget(PingRequest req, String candidate) {
        if (req == null || candidate == null || candidate.isBlank()) return false;
        String clean = candidate.trim();
        if (req.nodeCode != null && clean.equalsIgnoreCase(req.nodeCode)) return true;
        return req.nodeId != null && clean.equalsIgnoreCase(req.nodeId.toString());
    }
}
