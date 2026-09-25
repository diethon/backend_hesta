package com.hesta.backend.service.impl;

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
import com.hesta.backend.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryServiceImpl implements TelemetryService {

    private final DeviceRepository deviceRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final EdgeNodeRepository edgeNodeRepository;
    private final RealtimeEventPublisher realtimeEventPublisher;

    @Override
    @Transactional
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
            SensorReading reading = SensorReading.builder()
                    .device(device)
                    .metricType(payload.getMetricType())
                    .value(payload.getValue())
                    .unit(payload.getUnit() != null ? payload.getUnit() : "")
                    .recordedAt(OffsetDateTime.now())
                    .build();

            sensorReadingRepository.save(reading);
            log.info("Saved SensorReading for device [{}] ({}): {} {}",
                    device.getName(), payload.getMetricType(), payload.getValue(), payload.getUnit());

            // 3. Update Device live current_state and status
            Map<String, Object> currentState = device.getCurrentState();
            if (currentState == null) {
                currentState = new HashMap<>();
            }
            currentState.put(payload.getMetricType(), payload.getValue());
            device.setCurrentState(currentState);
            device.setStatus(DeviceStatus.ONLINE);
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

                Map<String, Object> eventData = Map.of(
                        "deviceId", device.getId(),
                        "deviceName", device.getName(),
                        "metricType", payload.getMetricType(),
                        "value", payload.getValue(),
                        "unit", payload.getUnit() != null ? payload.getUnit() : "",
                        "recordedAt", recordedAt.toString()
                );

                RealtimeEvent<Map<String, Object>> event = RealtimeEvent.create(
                        RealtimeEventType.SENSOR_READING_UPDATED,
                        homeId,
                        device.getId(),
                        eventData
                );

                realtimeEventPublisher.publish(event);
            }
        } catch (Exception e) {
            log.error("Failed to publish realtime telemetry event for device [{}]: {}", device.getId(), e.getMessage());
        }
    }
}
