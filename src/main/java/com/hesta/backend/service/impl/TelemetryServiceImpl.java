package com.hesta.backend.service.impl;

import com.hesta.backend.dto.event.TelemetryAnomalyEvent;
import com.hesta.backend.dto.request.TelemetryPayload;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.entity.SensorReading;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisher;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.EdgeNodeRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import com.hesta.backend.service.AnomalyDetectionService;
import com.hesta.backend.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class TelemetryServiceImpl implements TelemetryService {

    private final DeviceRepository deviceRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final EdgeNodeRepository edgeNodeRepository;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final AnomalyDetectionService anomalyDetectionService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    public TelemetryServiceImpl(
            DeviceRepository deviceRepository,
            SensorReadingRepository sensorReadingRepository,
            EdgeNodeRepository edgeNodeRepository,
            RealtimeEventPublisher realtimeEventPublisher,
            @Autowired(required = false) AnomalyDetectionService anomalyDetectionService,
            @Autowired(required = false) ApplicationEventPublisher applicationEventPublisher
    ) {
        this.deviceRepository = deviceRepository;
        this.sensorReadingRepository = sensorReadingRepository;
        this.edgeNodeRepository = edgeNodeRepository;
        this.realtimeEventPublisher = realtimeEventPublisher;
        this.anomalyDetectionService = anomalyDetectionService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Backward-compatible constructor for existing tests.
     */
    public TelemetryServiceImpl(
            DeviceRepository deviceRepository,
            SensorReadingRepository sensorReadingRepository,
            EdgeNodeRepository edgeNodeRepository,
            RealtimeEventPublisher realtimeEventPublisher
    ) {
        this(deviceRepository, sensorReadingRepository, edgeNodeRepository, realtimeEventPublisher, null, null);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void processTelemetry(String topic, TelemetryPayload payload) {
        if (payload == null) {
            log.warn("Telemetry payload is null for topic: {}", topic);
            return;
        }

        // 1. Resolve Device
        Optional<Device> deviceOpt = resolveDevice(topic, payload);
        if (deviceOpt.isEmpty()) {
            log.warn("Device not found for telemetry. Topic: {}, NodeId: {}, DeviceId: {}",
                    topic, payload.getNodeId(), payload.getDeviceId());
            return;
        }

        Device device = deviceOpt.get();

        // 2. Persist SensorReading
        if (payload.getValue() != null && payload.getMetricType() != null) {
            OffsetDateTime now = OffsetDateTime.now();
            SensorReading reading = SensorReading.builder()
                    .device(device)
                    .metricType(payload.getMetricType())
                    .value(payload.getValue())
                    .unit(payload.getUnit() != null ? payload.getUnit() : "")
                    .recordedAt(now)
                    .build();

            SensorReading saved = sensorReadingRepository.save(reading);
            log.info("Saved SensorReading for device [{}] ({}): {} {}",
                    device.getName(), payload.getMetricType(), payload.getValue(), payload.getUnit());

            // 3. Update Device live current_state (merge without overwriting other metrics), status, and lastSeen
            Map<String, Object> currentState = device.getCurrentState() != null
                    ? new HashMap<>(device.getCurrentState())
                    : new HashMap<>();
            currentState.put(payload.getMetricType(), payload.getValue());
            device.setCurrentState(currentState);
            device.setStatus(DeviceStatus.ONLINE);
            device.setLastSeen(now);
            deviceRepository.save(device);

            // 4. Update EdgeNode status if present
            if (device.getNode() != null) {
                EdgeNode node = device.getNode();
                if (node.getStatus() != EdgeNodeStatus.ONLINE) {
                    node.setStatus(EdgeNodeStatus.ONLINE);
                    edgeNodeRepository.save(node);
                }
            }

            // 5. Publish Realtime STOMP event if assigned to a home
            publishRealtimeEvent(device, payload, reading.getRecordedAt());

            // 6. Trigger Anomaly Detection after transaction commit
            if (applicationEventPublisher != null) {
                Long readingId = saved != null ? saved.getId() : null;
                OffsetDateTime recordedAt = saved != null ? saved.getRecordedAt() : reading.getRecordedAt();
                applicationEventPublisher.publishEvent(new TelemetryAnomalyEvent(
                        readingId,
                        device.getId(),
                        payload.getMetricType(),
                        payload.getValue(),
                        recordedAt
                ));
            }
        }
    }

    /**
     * Isolated post-commit handler: triggers Tier-1 rule evaluation in REQUIRES_NEW.
     * Guaranteed never to fail or roll back telemetry ingestion.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSensorReadingCommitted(TelemetryAnomalyEvent event) {
        if (event == null || anomalyDetectionService == null) return;
        try {
            Device device = deviceRepository.findById(event.deviceId()).orElse(null);
            if (device != null) {
                anomalyDetectionService.evaluateReading(
                        device,
                        event.metricType(),
                        event.value(),
                        event.recordedAt(),
                        event.readingId()
                );
            }
        } catch (Exception ex) {
            log.error("Error in anomaly evaluation after commit for device {} (readingId: {}, metric: {}): {}", event.deviceId(), event.readingId(), event.metricType(), ex.getMessage(), ex);
        }
    }

    private Optional<Device> resolveDevice(String topic, TelemetryPayload payload) {
        String deviceIdStr = payload.getDeviceId();

        // Try lookup by UUID if deviceId is a valid UUID
        if (deviceIdStr != null && !deviceIdStr.isBlank()) {
            try {
                UUID deviceUuid = UUID.fromString(deviceIdStr);
                Optional<Device> byId = deviceRepository.findById(deviceUuid);
                if (byId.isPresent()) {
                    return byId;
                }
            } catch (IllegalArgumentException ignored) {
                // Not a UUID, fallback to lookup by nodeCode and device name
            }
        }

        // Lookup by nodeCode and device name/code (e.g., nodeId = "ESP32-001", deviceId = "LDR-01")
        if (payload.getNodeId() != null && deviceIdStr != null) {
            Optional<Device> byNodeAndName = deviceRepository.findByNodeCodeAndName(payload.getNodeId(), deviceIdStr);
            if (byNodeAndName.isPresent()) {
                return byNodeAndName;
            }
        }

        // Lookup by MQTT topic if mapped
        if (topic != null && !topic.isBlank()) {
            return deviceRepository.findByMqttTopic(topic);
        }

        return Optional.empty();
    }

    private void publishRealtimeEvent(Device device, TelemetryPayload payload, OffsetDateTime recordedAt) {
        try {
            if (device.getNode() != null
                    && device.getRoom() != null
                    && device.getRoom().getHome() != null) {
                UUID homeId = device.getRoom().getHome().getId();
                UUID deviceId = device.getId();

                Map<String, Object> eventData = Map.of(
                        "deviceId", deviceId,
                        "deviceName", device.getName(),
                        "metricType", payload.getMetricType(),
                        "value", payload.getValue(),
                        "unit", payload.getUnit() != null ? payload.getUnit() : "",
                        "recordedAt", recordedAt.toString()
                );

                RealtimeEvent<Map<String, Object>> event = RealtimeEvent.create(
                        RealtimeEventType.SENSOR_READING_UPDATED,
                        homeId,
                        deviceId,
                        eventData
                );

                // Run outside the DB transaction so WebSocketRealtimeTransport's repository lookups
                // cannot flush pending writes or mark the telemetry transaction rollback-only on error.
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                realtimeEventPublisher.publish(event);
                            } catch (Exception e) {
                                log.error("Failed to publish realtime telemetry event for device [{}]: {}", deviceId, e.getMessage());
                            }
                        }
                    });
                } else {
                    realtimeEventPublisher.publish(event);
                }
            }
        } catch (Exception e) {
            log.error("Failed to publish realtime telemetry event for device [{}]: {}", device.getId(), e.getMessage());
        }
    }
}
