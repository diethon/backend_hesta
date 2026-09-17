package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.TwinHealthStatusChangedPayload;
import com.hesta.backend.enums.TwinHealthStatus;
import com.hesta.backend.enums.TwinNodeType;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisher;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import com.hesta.backend.repository.TwinHealthReference;
import com.hesta.backend.service.TwinHealthEvaluationService;
import com.hesta.backend.service.TwinHealthStatusResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** One per-instance transition cache; it never replaces the timestamp source of truth. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TwinHealthEvaluationServiceImpl implements TwinHealthEvaluationService {
    private final DeviceRepository deviceRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final TwinHealthStatusResolver resolver;
    private final Clock clock;
    private final RealtimeEventPublisher publisher;
    private final PlatformTransactionManager transactionManager;
    private final Map<NodeKey, TwinHealthStatus> statuses = new HashMap<>();

    // Serialize reads AND cache updates so a scan cannot overwrite a newer committed evaluation.
    @Override
    public synchronized void evaluateAll() {
        List<NodeReference> nodes = readCommitted(() -> {
            List<NodeReference> result = new ArrayList<>();
            deviceRepository.findHealthReferences().forEach(row -> result.add(new NodeReference(TwinNodeType.DEVICE, row)));
            sensorReadingRepository.findHealthReferences().forEach(row -> result.add(new NodeReference(TwinNodeType.SENSOR, row)));
            return result;
        });
        Instant now = clock.instant();
        var liveKeys = new HashSet<NodeKey>();
        nodes.forEach(node -> liveKeys.add(node.key()));
        statuses.keySet().retainAll(liveKeys);
        nodes.forEach(node -> evaluate(node, now));
    }

    @Override
    public synchronized void evaluateDevice(UUID deviceId) {
        var reference = readCommitted(() -> deviceRepository.findHealthReference(deviceId));
        reference.ifPresentOrElse(row -> evaluate(new NodeReference(TwinNodeType.DEVICE, row), clock.instant()),
                () -> statuses.remove(new NodeKey(TwinNodeType.DEVICE, deviceId.toString())));
    }

    @Override
    public synchronized void evaluateSensor(UUID deviceId, String metricType) {
        var reference = readCommitted(() -> sensorReadingRepository.findHealthReference(deviceId, metricType));
        reference.ifPresentOrElse(row -> evaluate(new NodeReference(TwinNodeType.SENSOR, row), clock.instant()),
                () -> statuses.remove(new NodeKey(TwinNodeType.SENSOR, TwinSnapshotMapper.sensorId(deviceId, metricType))));
    }

    private void evaluate(NodeReference node, Instant now) {
        TwinHealthStatus current = resolver.resolve(node.row().getReferenceTime(), now);
        TwinHealthStatus previous = statuses.get(node.key());
        if (previous != null && previous != current) {
            var payload = new TwinHealthStatusChangedPayload(node.type(), node.key().id(),
                    node.row().getDeviceId(), node.row().getRoomId(), previous, current,
                    node.row().getReferenceTime(), now);
            publisher.publish(RealtimeEvent.create(RealtimeEventType.TWIN_HEALTH_STATUS_CHANGED,
                    node.row().getHomeId(), node.row().getDeviceId(), payload));
            log.debug("Twin health changed: homeId={}, nodeType={}, nodeId={}, previous={}, current={}",
                    node.row().getHomeId(), node.type(), node.key().id(), previous, current);
        }
        // First sight is a silent baseline. A failed publication leaves the old status for retry.
        statuses.put(node.key(), current);
    }

    private <T> T readCommitted(Supplier<T> query) {
        // AFTER_COMMIT still binds the completed transaction's resources. Always read a fresh
        // committed snapshot, and finish it before publishing any derived transitions.
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return Objects.requireNonNull(transaction.execute(status -> query.get()));
    }

    private record NodeKey(TwinNodeType type, String id) { }

    private record NodeReference(TwinNodeType type, TwinHealthReference row) {
        NodeKey key() {
            return new NodeKey(type, type == TwinNodeType.DEVICE ? row.getDeviceId().toString()
                    : TwinSnapshotMapper.sensorId(row.getDeviceId(), row.getMetricType()));
        }
    }
}
