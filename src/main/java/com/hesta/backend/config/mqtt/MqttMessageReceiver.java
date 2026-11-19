package com.hesta.backend.config.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.dto.request.TelemetryPayload;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.AutomationEngine;
import com.hesta.backend.service.DeviceService;
import com.hesta.backend.repository.EdgeNodeRepository;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import org.springframework.context.ApplicationEventPublisher;
import java.util.List;
import com.hesta.backend.service.TelemetryService;
import com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MqttMessageReceiver {
    private final DeviceRepository devices;
    private final AutomationEngine automationEngine;
    private final ObjectMapper objectMapper;


    private final TelemetryService telemetryService;
    private final EdgeNodeRepository edgeNodeRepository;
    private final DeviceService deviceService;
    private final ApplicationEventPublisher applicationEvents;
    private final TwinSnapshotMapper twinSnapshotMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AutomationDispatcher automationDispatcher;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.diagnostics.MqttDiagnosticsService diagnosticsService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.diagnostics.MqttDlqService dlqService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.shadow.DeviceShadowService deviceShadowService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.ota.OtaCampaignService otaCampaignService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.falldetection.service.FallIncidentService fallIncidentService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.realtime.publisher.RealtimeEventPublisher realtimeEventPublisher;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.service.SecurityEventService securityEventService;

    /**
     * Receive all MQTT messages from mqttInputChannel.
     *
     * Supported topics:
     *
     * 1. Telemetry:
     *    hesta/nodes/{nodeId}/devices/{deviceId}/telemetry
     *    hesta/nodes/{nodeId}/devices/{deviceId}/sensor
     *
     * 2. Device state:
     *    hesta/nodes/{nodeId}/devices/{deviceId}/state
     *
     * 3. Command ACK:
     *    hesta/nodes/{nodeId}/devices/{deviceId}/ack
     */
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handleMessage(Message<?> message) {

        // =========================================================
        // 1. Get MQTT topic
        // =========================================================

        Object topicHeader = message.getHeaders()
                .get(MqttHeaders.RECEIVED_TOPIC);

        String topic = topicHeader != null
                ? topicHeader.toString()
                : "";

        // =========================================================
        // 2. Get payload
        // =========================================================

        String payload = message.getPayload().toString();

        if (diagnosticsService != null && !topic.isBlank()) {
            diagnosticsService.recordPacket(topic, "IN", 1, payload);
        }

        log.info(
                "Received MQTT message. Topic: [{}], Payload: [{}]",
                topic,
                payload
        );

        if (topic.isBlank()) {
            log.warn("Received MQTT message without topic");
            if (dlqService != null) {
                dlqService.recordDeadLetter("", payload, "MISSING_TOPIC", "UNKNOWN");
            }
            return;
        }

        String nodeCode = extractNodeCodeFromTopic(topic);
        if (dlqService != null && nodeCode != null && !dlqService.checkRateLimit(nodeCode)) {
            dlqService.recordDeadLetter(topic, payload, "RATE_LIMIT_EXCEEDED", nodeCode);
            log.warn("Dropped message from node [{}] due to rate limit violation (>50 msg/s)", nodeCode);
            return;
        }

        try {

            // =====================================================
            // 3. TELEMETRY / SENSOR
            // =====================================================

            if (topic.endsWith("/catalog")) {
                handleCatalog(topic, payload);
            }
            else if (topic.endsWith("/register") || topic.endsWith("/discovery")) {
                handleEdgeNodeRegistration(topic, payload);
            }
            else if (topic.endsWith("/telemetry") || topic.endsWith("/sensor")) {
                handleTelemetry(topic, payload);
            }

            // =====================================================
            // 4. DEVICE STATE
            // =====================================================

            else if (topic.endsWith("/state")) {

                handleDeviceState(topic, payload);
                processSensorAutomation(topic, payload);

            }

            // =====================================================
            // 5. COMMAND ACK
            // =====================================================

            else if (topic.endsWith("/ack") || topic.endsWith("/response")) {

                handleCommandAck(topic, payload);

            }

            // =====================================================
            // 6. DIAGNOSTICS PONG
            // =====================================================

            else if (topic.endsWith("/pong")) {

                handlePongMessage(nodeCode, payload);

            }

            // =====================================================
            // 7. OTA STATUS / ACK (must precede /status check!)
            // =====================================================

            else if (topic.contains("/ota/") && !topic.endsWith("/ota/notify") && !topic.endsWith("/ota/abort")) {

                handleOtaMessage(topic, payload);

            }

            // =====================================================
            // 8. DEVICE / NODE STATUS
            // =====================================================

            else if (topic.endsWith("/status")) {

                handleDeviceStatus(topic, payload);

            }

            // =====================================================
            // 8. FALL DETECTION / EMERGENCY SOS
            // =====================================================

            else if (topic.contains("fall-detection") || topic.contains("emergency/fall")) {

                handleFallDetectionEvent(topic, payload);

            }

            // =====================================================
            // 7. Unknown topic
            // =====================================================

            else {

                log.debug(
                        "Ignoring unsupported MQTT topic: [{}]",
                        topic
                );
            }

        } catch (Exception e) {

            log.error(
                    "Failed to process MQTT message. Topic: [{}], Payload: [{}]",
                    topic,
                    payload,
                    e
            );

            if (dlqService != null) {
                dlqService.recordDeadLetter(topic, payload, e.getClass().getSimpleName() + ": " + e.getMessage(), nodeCode);
            }
        }
    }


    // =============================================================
    // TELEMETRY
    // =============================================================

    private void handleTelemetry(
            String topic,
            String payload
    ) throws Exception {

        log.debug(
                "Processing telemetry message. Topic: [{}]",
                topic
        );

        TelemetryPayload telemetryPayload = null;
        try {
            telemetryPayload = objectMapper.readValue(payload, TelemetryPayload.class);
        } catch (Exception ignored) {}

        if (telemetryPayload != null && telemetryPayload.getMetricType() != null && telemetryPayload.getValue() != null) {
            telemetryService.processTelemetry(
                    topic,
                    telemetryPayload
            );
            processTelemetryAutomation(topic, telemetryPayload);
        } else {
            // Full device state telemetry (e.g., Air Conditioner telemetry or composite state)
            handleDeviceState(topic, payload);

            // Extract safety metrics from flat map if present: gas_ppm, smoke, temperature
            extractAndProcessSafetyMetricsFromFlatMap(topic, payload);

            try {
                String deviceIdStr = extractDeviceIdFromTopic(topic);
                if (deviceIdStr != null) {
                    try {
                        UUID deviceUuid = UUID.fromString(deviceIdStr);
                        Map<String, Object> stateMap = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
                        MqttDeviceCommandServiceImpl.completeDeviceCommand(deviceUuid, stateMap);
                    } catch (IllegalArgumentException ignored) {
                        String nodeCode = extractNodeCodeFromTopic(topic);
                        devices.findByNodeCodeAndLocalId(nodeCode, deviceIdStr)
                                .ifPresent(d -> {
                                    try {
                                        Map<String, Object> stateMap = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
                                        MqttDeviceCommandServiceImpl.completeDeviceCommand(d.getId(), stateMap);
                                    } catch (Exception ignoredInner) {}
                                });
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to check active command resolution from telemetry: {}", e.getMessage());
            }
        }

        if (topic.endsWith("/sensor")) {
            if (telemetryPayload == null || telemetryPayload.getMetricType() == null || telemetryPayload.getValue() == null) {
                processSensorAutomation(topic, payload);
            }
        }

        log.debug(
                "Telemetry processed successfully. Topic: [{}]",
                topic
        );
    }

    private void processTelemetryAutomation(String topic, TelemetryPayload payload) {
        if (payload == null) return;
        String topicNodeCode = extractNodeCodeFromTopic(topic);
        String nodeCode = payload.getNodeId() != null && !payload.getNodeId().isBlank() ? payload.getNodeId() : topicNodeCode;
        String deviceIdStr = payload.getDeviceId() != null && !payload.getDeviceId().isBlank() ? payload.getDeviceId() : extractDeviceIdFromTopic(topic);

        java.util.Optional<com.hesta.backend.entity.Device> deviceOpt = resolveDevice(topic, deviceIdStr, nodeCode);
        deviceOpt.ifPresent(device -> {
            Map<String, Object> data = new HashMap<>();
            if (device.getCurrentState() != null) {
                data.putAll(device.getCurrentState());
            }
            if (payload.getMetricType() != null && payload.getValue() != null) {
                applyMetricAliases(data, payload.getMetricType(), payload.getValue());
                Map<String, Object> incoming = new HashMap<>();
                applyMetricAliases(incoming, payload.getMetricType(), payload.getValue());
                recordMotionSecurityEvent(device, incoming);
            }
            triggerAutomation(device, data, "SENSOR");
        });
    }

    private void extractAndProcessSafetyMetricsFromFlatMap(String topic, String payload) {
        try {
            Map<String, Object> map = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            if (map == null || map.isEmpty()) return;
            // Actuators (e.g. AC) report a "temperature" setpoint, not a measured value.
            Object deviceType = map.get("deviceType");
            if (deviceType != null && !deviceType.toString().toUpperCase().contains("SENSOR")) return;

            String nodeCode = extractNodeCodeFromTopic(topic);
            String deviceIdStr = map.containsKey("deviceId") && map.get("deviceId") != null
                    ? map.get("deviceId").toString()
                    : extractDeviceIdFromTopic(topic);

            extractSingleSafetyMetric(topic, nodeCode, deviceIdStr, map, "gas_ppm", "ppm");
            extractSingleSafetyMetric(topic, nodeCode, deviceIdStr, map, "smoke", "ppm");
            extractSingleSafetyMetric(topic, nodeCode, deviceIdStr, map, "temperature", "°C");
            if (!map.containsKey("temperature")) {
                extractSingleSafetyMetric(topic, nodeCode, deviceIdStr, map, "temp", "°C");
            }
            extractSingleSafetyMetric(topic, nodeCode, deviceIdStr, map, "humidity", "%");
            if (!map.containsKey("humidity")) {
                extractSingleSafetyMetric(topic, nodeCode, deviceIdStr, map, "humid", "%");
            }
        } catch (Exception e) {
            log.warn("Failed to extract safety metrics from flat map payload: {}", e.getMessage());
        }
    }

    private void extractSingleSafetyMetric(String topic, String nodeCode, String deviceIdStr, Map<String, Object> map, String metricKey, String unit) {
        if (!map.containsKey(metricKey) || map.get(metricKey) == null) return;
        Object valObj = map.get(metricKey);
        try {
            java.math.BigDecimal val = new java.math.BigDecimal(valObj.toString().trim());
            String normalizedMetric = "temp".equalsIgnoreCase(metricKey) ? "temperature"
                    : "humid".equalsIgnoreCase(metricKey) ? "humidity"
                    : metricKey;
            TelemetryPayload singlePayload = new TelemetryPayload(nodeCode, deviceIdStr, normalizedMetric, val, unit);
            telemetryService.processTelemetry(topic, singlePayload);
        } catch (Exception ex) {
            log.warn("Could not parse safety metric [{}] value [{}] from flat map: {}", metricKey, valObj, ex.getMessage());
        }
    }

    private void processSensorAutomation(String topic, String payload) {
        try {
            String[] parts = topic.split("/");
            if (parts.length != 6 || !"hesta".equals(parts[0]) || !"nodes".equals(parts[1])
                    || !"devices".equals(parts[3])) {
                return;
            }

            String nodeCode = parts[2];
            String deviceIdStr = parts[4];

            Map<String, Object> parsedData = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> rawData = parsedData;
            if (parsedData.containsKey("state") && parsedData.get("state") instanceof Map<?, ?> stateMap) {
                rawData = (Map<String, Object>) stateMap;
            }

            java.util.Optional<com.hesta.backend.entity.Device> deviceOpt = resolveDevice(topic, deviceIdStr, nodeCode);
            if (deviceOpt.isEmpty() && parsedData.get("deviceId") != null) {
                deviceOpt = resolveDevice(topic, parsedData.get("deviceId").toString(), nodeCode);
            }

            final Map<String, Object> finalData = rawData;
            deviceOpt.ifPresent(device -> {
                Map<String, Object> data = new HashMap<>();
                if (device.getCurrentState() != null) {
                    data.putAll(device.getCurrentState());
                }
                data.putAll(finalData);

                Map<String, Object> incoming = new HashMap<>(finalData);
                if (finalData.containsKey("metricType") && finalData.get("metricType") != null && finalData.get("value") != null) {
                    applyMetricAliases(data, finalData.get("metricType").toString(), finalData.get("value"));
                    applyMetricAliases(incoming, finalData.get("metricType").toString(), finalData.get("value"));
                }
                recordMotionSecurityEvent(device, incoming);

                String deviceType = device.getDeviceType() != null ? device.getDeviceType().toUpperCase() : "";
                boolean isSensor = deviceType.endsWith("SENSOR") || deviceType.contains("SENSOR") || "CAMERA_AI".equals(deviceType);
                String eventType = isSensor ? "SENSOR" : "EVENT";

                triggerAutomation(device, data, eventType);
            });
        } catch (Exception e) {
            log.warn("Could not process automation from MQTT message: {}", e.getMessage());
        }
    }

    private void triggerAutomation(com.hesta.backend.entity.Device device, Map<String, Object> data, String eventType) {
        if (device.getRoom() == null || device.getRoom().getHome() == null) {
            return;
        }
        UUID homeId = device.getRoom().getHome().getId();
        UUID deviceId = device.getId();
        AutomationEventRequest event = AutomationEventRequest.builder()
                .sourceDeviceId(deviceId)
                .eventType(eventType)
                .data(new HashMap<>(data))
                .stateReport(true)
                .build();
        Runnable task = () -> {
            try {
                automationEngine.process(homeId, event);
            } catch (Exception ex) {
                log.warn("AutomationEngine execution error for device {}: {}", deviceId, ex.getMessage());
            }
        };
        // Async: own transaction (an engine failure can no longer roll back this telemetry write)
        // and the MQTT thread stays free to deliver the ACKs the engine waits for.
        if (automationDispatcher != null) automationDispatcher.dispatch(task);
        else task.run();
    }

    /** {@code incoming} must hold only the new message's values: saved device state can be stale. */
    private void recordMotionSecurityEvent(com.hesta.backend.entity.Device device, Map<String, Object> incoming) {
        if (securityEventService == null || device.getDeviceType() == null
                || !device.getDeviceType().toUpperCase().contains("MOTION")
                || !(isMotionDetected(incoming.get("motion")) || isMotionDetected(incoming.get("MOTION_SENSOR"))
                        || isMotionDetected(incoming.get("motionDetected")))) {
            return;
        }
        try {
            securityEventService.recordMotion(device);
        } catch (Exception ex) {
            // Security history is best-effort; it must never block telemetry or automation.
            log.warn("Failed to record motion security event for device {}: {}", device.getId(), ex.getMessage());
        }
    }

    static boolean isMotionDetected(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) return number.doubleValue() > 0;
        if (value == null) return false;
        String text = value.toString().trim();
        return text.equalsIgnoreCase("true") || text.equals("1") || text.equalsIgnoreCase("DETECTED")
                || text.equalsIgnoreCase("ON");
    }

    private void applyMetricAliases(Map<String, Object> data, String metricType, Object value) {
        data.put(metricType, value);
        data.put(metricType.toLowerCase(), value);
        data.put(metricType.toUpperCase(), value);
        data.put("value", value);

        if ("temperature".equalsIgnoreCase(metricType) || "temp".equalsIgnoreCase(metricType)) {
            data.put("temperature", value);
            data.put("temp", value);
        } else if ("humidity".equalsIgnoreCase(metricType) || "hum".equalsIgnoreCase(metricType)) {
            data.put("humidity", value);
            data.put("hum", value);
        } else if ("light_sensor".equalsIgnoreCase(metricType) || "light".equalsIgnoreCase(metricType)) {
            data.put("LIGHT_SENSOR", value);
            data.put("light", value);
            data.put("lux", value);
        } else if ("motion_sensor".equalsIgnoreCase(metricType) || "motion".equalsIgnoreCase(metricType)) {
            data.put("MOTION_SENSOR", value);
            data.put("motion", value);
            data.put("motionDetected", value);
        } else if ("flame_sensor".equalsIgnoreCase(metricType) || "flame".equalsIgnoreCase(metricType) || "fire".equalsIgnoreCase(metricType)) {
            data.put("FLAME_SENSOR", value);
            data.put("flame", value);
            data.put("fire", value);
            data.put("flameDetected", value);
        } else if ("smoke_sensor".equalsIgnoreCase(metricType) || "smoke".equalsIgnoreCase(metricType) || "gas".equalsIgnoreCase(metricType)) {
            data.put("SMOKE_SENSOR", value);
            data.put("smoke", value);
            data.put("gas", value);
            data.put("smokeDetected", value);
        }
    }

    private java.util.Optional<com.hesta.backend.entity.Device> resolveDevice(String topic, String deviceIdStr, String nodeCode) {
        if (deviceIdStr != null && !deviceIdStr.isBlank()) {
            try {
                UUID uuid = UUID.fromString(deviceIdStr);
                java.util.Optional<com.hesta.backend.entity.Device> byId = devices.findByIdWithNode(uuid);
                if (byId.isPresent()) return byId;
            } catch (IllegalArgumentException ignored) {}
        }
        if (nodeCode != null && deviceIdStr != null) {
            java.util.Optional<com.hesta.backend.entity.Device> byNodeAndLocal = devices.findByNodeCodeAndLocalIdWithRoomAndHome(nodeCode, deviceIdStr);
            if (byNodeAndLocal.isPresent()) return byNodeAndLocal;

            java.util.Optional<com.hesta.backend.entity.Device> byNodeAndName = devices.findByNodeCodeAndNameWithRoomAndHome(nodeCode, deviceIdStr);
            if (byNodeAndName.isPresent()) return byNodeAndName;
        }
        if (topic != null && !topic.isBlank()) {
            return devices.findByMqttTopicWithRoomAndHome(topic);
        }
        return java.util.Optional.empty();
    }


    // =============================================================
    // DEVICE STATE
    // =============================================================

    private void handleDeviceState(
            String topic,
            String payload
    ) throws Exception {

        Map<String, Object> data =
                objectMapper.readValue(
                        payload,
                        new TypeReference<Map<String, Object>>() {}
                );

        /*
         * Example payload:
         *
         * {
         *     "deviceId": "xxx",
         *     "state": {
         *         "power": true
         *     }
         * }
         */

        String deviceId = null;

        // ---------------------------------------------------------
        // Priority 1:
        // Get deviceId from payload
        // ---------------------------------------------------------

        if (data.get("deviceId") != null) {

            deviceId =
                    data.get("deviceId").toString();
        }

        // ---------------------------------------------------------
        // Priority 2:
        // If payload does not contain deviceId,
        // extract it from MQTT topic.
        //
        // hesta/nodes/{nodeId}/devices/{deviceId}/state
        //                    0      1        2        3      4
        // ---------------------------------------------------------

        if (deviceId == null || deviceId.isBlank()) {

            deviceId = extractDeviceIdFromTopic(topic);
        }

        if (deviceId == null) {

            log.warn(
                    "Cannot determine deviceId from state message. Topic: [{}]",
                    topic
            );

            return;
        }

        // ---------------------------------------------------------
        // Get state
        // ---------------------------------------------------------

        Object stateObject = data.get("state");

        // Release the waiting command before the slow remote-DB writes below, so the ACK is not late.
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> ackState = stateObject instanceof Map<?, ?> ? (Map<String, Object>) stateObject : data;
            MqttDeviceCommandServiceImpl.completeDeviceCommand(UUID.fromString(deviceId), ackState);
        } catch (IllegalArgumentException localId) {
            // Not a UUID: resolved by node + localId lookup at the end of this method.
        }

        if (stateObject instanceof Map<?, ?>) {

            @SuppressWarnings("unchecked")
            Map<String, Object> state =
                    (Map<String, Object>) stateObject;

            String nodeCode = extractNodeCodeFromTopic(topic);
            deviceService.updateDeviceStateFromMqtt(
                    nodeCode,
                    deviceId,
                    state
            );

            log.info(
                    "Device state updated. DeviceId: [{}], State: {}",
                    deviceId,
                    state
            );

        } else {

            /*
             * Support payload where the entire JSON is the state.
             *
             * Example:
             *
             * {
             *     "power": true,
             *     "brightness": 80
             * }
             *
             * instead of:
             *
             * {
             *     "deviceId": "...",
             *     "state": {
             *         "power": true,
             *         "brightness": 80
             *     }
             * }
             */

            if (!data.isEmpty()) {

                String nodeCode = extractNodeCodeFromTopic(topic);
                deviceService.updateDeviceStateFromMqtt(
                        nodeCode,
                        deviceId,
                        data
                );

                log.info(
                        "Device state updated from direct payload. DeviceId: [{}], State: {}",
                        deviceId,
                        data
                );
            }
        }

        try {
            if (deviceId != null) {
                Map<String, Object> resolvedState = (stateObject instanceof Map<?, ?>)
                        ? (Map<String, Object>) stateObject
                        : data;
                try {
                    UUID deviceUuid = UUID.fromString(deviceId);
                    MqttDeviceCommandServiceImpl.completeDeviceCommand(deviceUuid, resolvedState);
                    if (deviceShadowService != null) {
                        Long incomingVersion = (data.get("version") instanceof Number num) ? num.longValue() : null;
                        deviceShadowService.updateReportedState(deviceUuid, resolvedState, incomingVersion);
                    }
                } catch (IllegalArgumentException ignored) {
                    String nodeCode = extractNodeCodeFromTopic(topic);
                    devices.findByNodeCodeAndLocalId(nodeCode, deviceId)
                            .ifPresent(d -> {
                                MqttDeviceCommandServiceImpl.completeDeviceCommand(d.getId(), resolvedState);
                                if (deviceShadowService != null) {
                                    Long incomingVersion = (data.get("version") instanceof Number num) ? num.longValue() : null;
                                    deviceShadowService.updateReportedState(d.getId(), resolvedState, incomingVersion);
                                }
                            });
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check active command resolution from state: {}", e.getMessage());
        }
    }


    // =============================================================
    // COMMAND ACK
    // =============================================================

    private void handleCommandAck(
            String topic,
            String payload
    ) throws Exception {

        Map<String, Object> data =
                objectMapper.readValue(
                        payload,
                        new TypeReference<Map<String, Object>>() {}
                );

        /*
         * Example ACK:
         *
         * {
         *     "commandId": "abc-123",
         *     "deviceId": "device-001",
         *     "status": "SUCCESS",
         *     "errorCode": null,
         *     "state": {
         *         "power": true
         *     }
         * }
         */

        String commandId =
                data.get("commandId") != null
                        ? data.get("commandId").toString()
                        : null;

        if (commandId == null || commandId.isBlank()) {
            String fallbackDeviceId = data.get("deviceId") != null
                    ? data.get("deviceId").toString()
                    : extractDeviceIdFromTopic(topic);
            if (fallbackDeviceId != null && !fallbackDeviceId.isBlank()) {
                completeActiveCommandFromAckFallback(topic, fallbackDeviceId, data);
                if (data.get("deviceId") == null) {
                    data.put("deviceId", fallbackDeviceId);
                }
                if (data.containsKey("state")) {
                    handleLateAckStateReconciliation(data);
                }
                log.info("Processed ACK without commandId via deviceId fallback: [{}]", fallbackDeviceId);
                return;
            }

            log.warn(
                    "Received ACK without commandId or deviceId. Topic: [{}], Payload: [{}]",
                    topic,
                    payload
            );

            return;
        }

        // =========================================================
        // Check pending command
        // =========================================================

        CompletableFuture<CommandResult> future =
                MqttDeviceCommandServiceImpl.pendingCommands.remove(commandId);

        UUID mappedDeviceId = MqttDeviceCommandServiceImpl.commandDeviceMap.get(commandId);
        if (data.get("deviceId") == null && mappedDeviceId != null) {
            data.put("deviceId", mappedDeviceId.toString());
        }

        if (future != null) {

            // -----------------------------------------------------
            // Determine success
            // -----------------------------------------------------

            String status =
                    data.get("status") != null
                            ? data.get("status").toString()
                            : "UNKNOWN";

            String errorCode =
                    data.get("errorCode") != null
                            ? data.get("errorCode").toString()
                            : null;

            String statusUpper = status.trim().toUpperCase(java.util.Locale.ROOT);
            Object successObj = data.get("success");
            boolean success = false;
            if (successObj instanceof Boolean) {
                success = (Boolean) successObj;
            } else if (successObj != null && ("true".equalsIgnoreCase(successObj.toString()) || "1".equals(successObj.toString()))) {
                success = true;
            } else {
                success = "SUCCESS".equals(statusUpper)
                        || "OK".equals(statusUpper)
                        || "ACCEPTED".equals(statusUpper)
                        || "ACK".equals(statusUpper)
                        || "DONE".equals(statusUpper)
                        || "CLOSING".equals(statusUpper)
                        || "OPENING".equals(statusUpper)
                        || "CLOSED".equals(statusUpper)
                        || "OPEN".equals(statusUpper);
            }

            if (errorCode != null && !errorCode.isBlank() && !"NONE".equalsIgnoreCase(errorCode) && !"0".equals(errorCode)) {
                if (!"SUCCESS".equals(statusUpper) && !"OK".equals(statusUpper) && !"ACCEPTED".equals(statusUpper)) {
                    success = false;
                }
            }

            // -----------------------------------------------------
            // Build command result
            // -----------------------------------------------------

            CommandResult result =
                    CommandResult.builder()
                            .commandId(commandId)
                            .success(success)
                            .status(status)
                            .errorCode(errorCode)
                            .message("Received ACK")
                            .build();

            // -----------------------------------------------------
            // Complete waiting command
            // -----------------------------------------------------

            future.complete(result);

            log.info(
                    "Command ACK processed. CommandId: [{}], Status: [{}], Success: [{}]",
                    commandId,
                    status,
                    success
            );

            if (success && data.containsKey("state")) {
                handleLateAckStateReconciliation(data);
            }

        } else {

            // =====================================================
            // Late / duplicate ACK or mismatched commandId
            // =====================================================

            String fallbackDeviceId = data.get("deviceId") != null
                    ? data.get("deviceId").toString()
                    : extractDeviceIdFromTopic(topic);
            if (fallbackDeviceId != null && !fallbackDeviceId.isBlank()) {
                completeActiveCommandFromAckFallback(topic, fallbackDeviceId, data);
            }

            log.warn(
                    "Received late or duplicate ACK for commandId: [{}]. "
                            + "The original command may have timed out.",
                    commandId
            );

            handleLateAckStateReconciliation(data);
        }
    }

    @SuppressWarnings("unchecked")
    private void completeActiveCommandFromAckFallback(String topic, String deviceIdStr, Map<String, Object> data) {
        String status = data.get("status") != null ? data.get("status").toString().trim().toUpperCase(java.util.Locale.ROOT) : "";
        Object successObj = data.get("success");
        boolean isFailure = Boolean.FALSE.equals(successObj)
                || "false".equalsIgnoreCase(String.valueOf(successObj))
                || "FAILED".equals(status)
                || "ERROR".equals(status)
                || "REJECTED".equals(status);
        if (isFailure) {
            return;
        }
        Map<String, Object> ackState = (data.get("state") instanceof Map<?, ?> m) ? (Map<String, Object>) m : data;
        try {
            MqttDeviceCommandServiceImpl.completeDeviceCommand(UUID.fromString(deviceIdStr), ackState);
        } catch (IllegalArgumentException ignored) {
            String nodeCode = extractNodeCodeFromTopic(topic);
            devices.findByNodeCodeAndLocalId(nodeCode, deviceIdStr)
                    .ifPresent(d -> MqttDeviceCommandServiceImpl.completeDeviceCommand(d.getId(), ackState));
        }
    }


    // =============================================================
    // LATE ACK STATE RECONCILIATION
    // =============================================================

    private void handleLateAckStateReconciliation(
            Map<String, Object> data
    ) {

        String deviceId =
                data.get("deviceId") != null
                        ? data.get("deviceId").toString()
                        : null;

        String commandId =
                data.get("commandId") != null
                        ? data.get("commandId").toString()
                        : null;

        if ((deviceId == null || deviceId.isBlank()) && commandId != null) {
            UUID mapped = MqttDeviceCommandServiceImpl.commandDeviceMap.remove(commandId);
            if (mapped != null) {
                deviceId = mapped.toString();
                data.put("deviceId", deviceId);
            }
        } else if (commandId != null) {
            MqttDeviceCommandServiceImpl.commandDeviceMap.remove(commandId);
        }

        if (deviceId == null || deviceId.isBlank()) {

            log.warn(
                    "Late ACK does not contain deviceId. Cannot reconcile state."
            );

            return;
        }

        Object stateObject =
                data.get("state");

        if (!(stateObject instanceof Map<?, ?>)) {

            log.debug(
                    "Late ACK does not contain a valid state. DeviceId: [{}]",
                    deviceId
            );

            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> state =
                (Map<String, Object>) stateObject;

        log.info(
                "Applying state reconciliation for late ACK. "
                        + "DeviceId: [{}], State: {}",
                deviceId,
                state
        );

        // Cannot easily determine nodeCode here without fetching device by UUID
        // Assuming late ACK deviceId is a UUID since commands are initiated by backend using UUID
        deviceService.updateDeviceStateFromMqtt(
                null,
                deviceId,
                state
        );
    }


    // =============================================================
    // EXTRACT DEVICE ID FROM TOPIC
    // =============================================================

    private String extractNodeCodeFromTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        String[] parts = topic.split("/");
        if (parts.length >= 3 && "nodes".equals(parts[1]) && !parts[2].isBlank()) {
            return parts[2];
        }
        return null;
    }

    private String extractDeviceIdFromTopic(
            String topic
    ) {

        /*
         * Expected:
         *
         * hesta/nodes/{nodeId}/devices/{deviceId}/state
         *
         * split:
         *
         * [0] hesta
         * [1] nodes
         * [2] nodeId
         * [3] devices
         * [4] deviceId
         * [5] state
         */

        String[] parts = topic.split("/");

        if (parts.length >= 6
                && "devices".equals(parts[3])) {

            return parts[4];
        }

        log.warn(
                "Invalid MQTT topic format. Cannot extract deviceId: [{}]",
                topic
        );

        return null;
    }
    private java.util.Optional<EdgeNode> resolveEdgeNode(String nodeIdent) {
        if (nodeIdent == null || nodeIdent.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            java.util.Optional<EdgeNode> byId = edgeNodeRepository.findById(UUID.fromString(nodeIdent));
            if (byId.isPresent()) {
                return byId;
            }
        } catch (IllegalArgumentException ignored) {}
        return edgeNodeRepository.findByNodeCode(nodeIdent);
    }

    private boolean isOnlinePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return true;
        }
        String trimmed = payload.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> data = objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
                Object val = data.get("status");
                if (val == null) {
                    val = data.get("state");
                }
                if (val == null) {
                    return true;
                }
                if (val instanceof Boolean b) {
                    return b;
                }
                String strVal = val.toString().trim();
                return "ONLINE".equalsIgnoreCase(strVal) || "ON".equalsIgnoreCase(strVal) || "TRUE".equalsIgnoreCase(strVal);
            } catch (Exception ignored) {
                return false;
            }
        }
        String unquoted = trimmed.replace("\"", "").trim();
        return "ONLINE".equalsIgnoreCase(unquoted) || "1".equals(unquoted) || "TRUE".equalsIgnoreCase(unquoted);
    }

    private void handleCatalog(String topic, String payload) {
        log.info("Processing catalog. Topic: [{}]", topic);
        try {
            // Topic format: hesta/nodes/{nodeId}/catalog
            String[] parts = topic.split("/");
            if (parts.length >= 4) {
                String nodeId = parts[2];
                java.util.Optional<EdgeNode> nodeOpt = resolveEdgeNode(nodeId);
                if (nodeOpt.isPresent()) {
                    EdgeNode node = nodeOpt.get();
                    List<Map<String, Object>> supportedTypes = objectMapper.readValue(payload, new TypeReference<List<Map<String, Object>>>() {});
                    node.setSupportedTypes(supportedTypes);
                    edgeNodeRepository.save(node);
                    log.info("Updated catalog for node [{}]: {}", nodeId, supportedTypes);
                } else {
                    log.warn("Node not found for catalog update: {}", nodeId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse catalog payload", e);
        }
    }

    private void handleEdgeNodeRegistration(String topic, String payload) {
        log.info("Processing EdgeNode registration. Topic: [{}], Payload: [{}]", topic, payload);
        try {
            String nodeCode = null;
            String firmwareVersion = null;
            String deviceType = null;
            try {
                Map<String, Object> data = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
                if (data.containsKey("nodeId") && data.get("nodeId") != null) {
                    nodeCode = data.get("nodeId").toString();
                } else if (data.containsKey("nodeCode") && data.get("nodeCode") != null) {
                    nodeCode = data.get("nodeCode").toString();
                }
                if (data.get("firmwareVersion") != null && !data.get("firmwareVersion").toString().isBlank()) {
                    firmwareVersion = data.get("firmwareVersion").toString().trim();
                }
                if (data.get("deviceType") != null && !data.get("deviceType").toString().isBlank()) {
                    deviceType = data.get("deviceType").toString().trim();
                }
            } catch (Exception ignored) {}

            if (nodeCode == null || nodeCode.isBlank()) {
                String[] parts = topic.split("/");
                if (parts.length >= 3 && "nodes".equals(parts[1])) {
                    nodeCode = parts[2];
                }
            }

            if (nodeCode != null && !nodeCode.isBlank()) {
                final String finalNodeCode = nodeCode;
                final String finalFw = firmwareVersion;
                final String finalType = deviceType;
                resolveEdgeNode(finalNodeCode).ifPresent(node -> {
                    node.setStatus(com.hesta.backend.enums.EdgeNodeStatus.ONLINE);
                    if (finalFw != null) {
                        node.setFirmwareVersion(finalFw);
                    }
                    if (finalType != null) {
                        node.setDeviceType(finalType);
                    }
                    edgeNodeRepository.save(node);
                    log.info("EdgeNode [{}] registered and set to ONLINE", finalNodeCode);
                    cascadeNodeStatusToDevices(node, com.hesta.backend.enums.DeviceStatus.ONLINE);
                });
            }
        } catch (Exception e) {
            log.error("Failed to process EdgeNode registration for topic: {}", topic, e);
        }
    }

    private void handleDeviceStatus(String topic, String payload) {
        log.info("Processing status. Topic: [{}], Status: [{}]", topic, payload);
        try {
            boolean online = isOnlinePayload(payload);
            String[] parts = topic.split("/");
            if (parts.length == 4 && "nodes".equals(parts[1]) && "status".equals(parts[3])) {
                String nodeCode = parts[2];
                com.hesta.backend.enums.EdgeNodeStatus nodeStatus = online
                        ? com.hesta.backend.enums.EdgeNodeStatus.ONLINE
                        : com.hesta.backend.enums.EdgeNodeStatus.OFFLINE;
                com.hesta.backend.enums.DeviceStatus nodeDevStatus = online
                        ? com.hesta.backend.enums.DeviceStatus.ONLINE
                        : com.hesta.backend.enums.DeviceStatus.OFFLINE;
                resolveEdgeNode(nodeCode).ifPresent(n -> {
                    n.setStatus(nodeStatus);
                    edgeNodeRepository.save(n);
                    log.info("Updated node {} status to {}", nodeCode, nodeStatus);
                    cascadeNodeStatusToDevices(n, nodeDevStatus);
                });
                return;
            }

            String deviceIdStr = extractDeviceIdFromTopic(topic);
            String nodeCode = extractNodeCodeFromTopic(topic);
            if (deviceIdStr == null) return;

            com.hesta.backend.enums.DeviceStatus devStatus = online
                    ? com.hesta.backend.enums.DeviceStatus.ONLINE
                    : com.hesta.backend.enums.DeviceStatus.OFFLINE;

            java.util.Optional<com.hesta.backend.entity.Device> devOpt = resolveDevice(topic, deviceIdStr, nodeCode);
            if (devOpt.isEmpty()) {
                try {
                    devOpt = devices.findById(UUID.fromString(deviceIdStr));
                } catch (IllegalArgumentException e) {
                    devOpt = devices.findByNodeCodeAndLocalId(nodeCode, deviceIdStr);
                }
            }

            devOpt.ifPresent(device -> {
                if (devStatus == com.hesta.backend.enums.DeviceStatus.OFFLINE
                        && nodeCode != null && !nodeCode.isBlank()
                        && device.getNode() != null) {
                    EdgeNode boundNode = device.getNode();
                    boolean matchesNode = nodeCode.equalsIgnoreCase(boundNode.getNodeCode())
                            || (boundNode.getId() != null && nodeCode.equalsIgnoreCase(boundNode.getId().toString()));
                    if (!matchesNode) {
                        log.warn("Ignoring foreign OFFLINE status for device {} from topic node {} (bound node: {} / {})",
                                device.getId(), nodeCode, boundNode.getNodeCode(), boundNode.getId());
                        return;
                    }
                }

                boolean statusChanged = device.getStatus() != devStatus;
                // A heartbeat that changes nothing must not save or broadcast: its snapshot can be older than a command
                // committed meanwhile and would flip other screens back. Freshness is handled by twin health.
                if (statusChanged) {
                    device.setStatus(devStatus);
                    devices.save(device);
                    log.info("Updated device {} status to {}", device.getId(), devStatus);
                    publishDeviceStateChanged(device);
                }

                if (devStatus == com.hesta.backend.enums.DeviceStatus.ONLINE) {
                    if (nodeCode != null && !nodeCode.isBlank()) {
                        resolveEdgeNode(nodeCode).ifPresent(n -> {
                            if (n.getStatus() != com.hesta.backend.enums.EdgeNodeStatus.ONLINE) {
                                n.setStatus(com.hesta.backend.enums.EdgeNodeStatus.ONLINE);
                                edgeNodeRepository.save(n);
                            }
                        });
                    }
                    if (device.getNode() != null) {
                        try {
                            EdgeNode node = device.getNode();
                            if (node.getStatus() != com.hesta.backend.enums.EdgeNodeStatus.ONLINE) {
                                node.setStatus(com.hesta.backend.enums.EdgeNodeStatus.ONLINE);
                                edgeNodeRepository.save(node);
                            }
                        } catch (Exception ex) {
                            log.debug("Could not verify node status for device {}: {}", device.getId(), ex.getMessage());
                        }
                    }
                }
            });
        } catch (Exception e) {
            log.error("Failed to process status from topic: {}", topic, e);
        }
    }

    private void handlePongMessage(String nodeCode, String payload) {
        if (diagnosticsService == null) return;
        diagnosticsService.handlePong(nodeCode, payload);
    }

    private void handleOtaMessage(String topic, String payload) {
        if (otaCampaignService == null) return;
        try {
            String nodeCode = extractNodeCodeFromTopic(topic);
            Map<String, Object> data = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            String campaignId = data.get("campaignId") != null ? data.get("campaignId").toString() : null;
            String version = data.getOrDefault("version", "").toString();
            String status = data.getOrDefault("status", "").toString();
            Integer progress = null;
            if (data.get("progress") instanceof Number num) {
                progress = num.intValue();
            }
            String error = data.get("error") != null ? data.get("error").toString() : "";
            otaCampaignService.handleNodeOtaResult(nodeCode, campaignId, version, status, progress, error);
            log.info("Processed OTA status for node [{}]: campaignId={}, status={}, version={}",
                    nodeCode, campaignId, status, version);
        } catch (Exception e) {
            log.error("Failed to parse OTA message from topic [{}]: {}", topic, e.getMessage());
        }
    }

    private void handleFallDetectionEvent(String topic, String payload) {
        log.info("Processing Fall Detection MQTT event from topic: {}", topic);
        if (fallIncidentService != null) {
            try {
                com.hesta.backend.falldetection.dto.FallIncidentEventDTO event =
                        objectMapper.readValue(payload, com.hesta.backend.falldetection.dto.FallIncidentEventDTO.class);
                fallIncidentService.processIncomingFallEvent(event);
            } catch (Exception e) {
                log.error("Failed to parse or process fall incident payload: {}", payload, e);
            }
        } else {
            log.warn("FallIncidentService is not available to process fall event");
        }
    }

    private void cascadeNodeStatusToDevices(EdgeNode node, com.hesta.backend.enums.DeviceStatus newStatus) {
        if (devices == null || node == null) return;
        try {
            List<com.hesta.backend.entity.Device> devList = devices.findByNodeIdOrNodeCode(node.getId(), node.getNodeCode());
            for (com.hesta.backend.entity.Device dev : devList) {
                if (dev.getStatus() != newStatus) {
                    dev.setStatus(newStatus);
                    dev.setLastSeen(java.time.OffsetDateTime.now());
                    devices.save(dev);
                    log.info("Cascaded status {} to device {} ({}) for node {}", newStatus, dev.getName(), dev.getId(), node.getNodeCode());
                    publishDeviceStateChanged(dev);
                }
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

}
