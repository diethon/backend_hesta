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
@Transactional
public class MqttMessageReceiver {
    private final DeviceRepository devices;
    private final AutomationEngine automationEngine;
    private final ObjectMapper objectMapper;


    private final TelemetryService telemetryService;
    private final EdgeNodeRepository edgeNodeRepository;
    private final DeviceService deviceService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.diagnostics.MqttDiagnosticsService diagnosticsService;

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

            else if (topic.endsWith("/ack")) {

                handleCommandAck(payload);

            }

            // =====================================================
            // 6. DEVICE STATUS
            // =====================================================

            else if (topic.endsWith("/status")) {

                handleDeviceStatus(topic, payload);

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
            }
            triggerAutomation(device, data, "SENSOR");
        });
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

                if (finalData.containsKey("metricType") && finalData.get("metricType") != null && finalData.get("value") != null) {
                    applyMetricAliases(data, finalData.get("metricType").toString(), finalData.get("value"));
                }

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
        try {
            automationEngine.process(
                    device.getRoom().getHome().getId(),
                    AutomationEventRequest.builder()
                            .sourceDeviceId(device.getId())
                            .eventType(eventType)
                            .data(data)
                            .build()
            );
        } catch (Exception ex) {
            log.warn("AutomationEngine execution error for device {}: {}", device.getId(), ex.getMessage());
        }
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
        }
    }

    private java.util.Optional<com.hesta.backend.entity.Device> resolveDevice(String topic, String deviceIdStr, String nodeCode) {
        if (deviceIdStr != null && !deviceIdStr.isBlank()) {
            try {
                UUID uuid = UUID.fromString(deviceIdStr);
                java.util.Optional<com.hesta.backend.entity.Device> byId = devices.findById(uuid);
                if (byId.isPresent()) return byId;
            } catch (IllegalArgumentException ignored) {}
        }
        if (nodeCode != null && deviceIdStr != null) {
            java.util.Optional<com.hesta.backend.entity.Device> byNodeAndLocal = devices.findByNodeCodeAndLocalId(nodeCode, deviceIdStr);
            if (byNodeAndLocal.isPresent()) return byNodeAndLocal;

            java.util.Optional<com.hesta.backend.entity.Device> byNodeAndName = devices.findByNodeCodeAndName(nodeCode, deviceIdStr);
            if (byNodeAndName.isPresent()) return byNodeAndName;
        }
        if (topic != null && !topic.isBlank()) {
            return devices.findByMqttTopic(topic);
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
                } catch (IllegalArgumentException ignored) {
                    String nodeCode = extractNodeCodeFromTopic(topic);
                    devices.findByNodeCodeAndLocalId(nodeCode, deviceId)
                            .ifPresent(d -> MqttDeviceCommandServiceImpl.completeDeviceCommand(d.getId(), resolvedState));
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

            log.warn(
                    "Received ACK without commandId. Payload: [{}]",
                    payload
            );

            return;
        }

        // =========================================================
        // Check pending command
        // =========================================================

        if (MqttDeviceCommandServiceImpl.pendingCommands
                .containsKey(commandId)) {

            CompletableFuture<CommandResult> future =
                    MqttDeviceCommandServiceImpl.pendingCommands
                            .remove(commandId);

            // -----------------------------------------------------
            // Determine success
            // -----------------------------------------------------

            boolean success =
                    "SUCCESS".equalsIgnoreCase(
                            String.valueOf(
                                    data.get("status")
                            )
                    );

            String status =
                    data.get("status") != null
                            ? data.get("status").toString()
                            : "UNKNOWN";

            String errorCode =
                    data.get("errorCode") != null
                            ? data.get("errorCode").toString()
                            : null;

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
            // Late / duplicate ACK
            // =====================================================

            log.warn(
                    "Received late or duplicate ACK for commandId: [{}]. "
                            + "The original command may have timed out.",
                    commandId
            );

            handleLateAckStateReconciliation(data);
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
        String[] parts = topic.split("/");
        if (parts.length >= 6 && "nodes".equals(parts[1])) {
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
    private void handleCatalog(String topic, String payload) {
        log.info("Processing catalog. Topic: [{}]", topic);
        try {
            // Topic format: hesta/nodes/{nodeId}/catalog
            String[] parts = topic.split("/");
            if (parts.length >= 4) {
                String nodeId = parts[2];
                java.util.Optional<EdgeNode> nodeOpt = edgeNodeRepository.findByNodeCode(nodeId);
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
            try {
                Map<String, Object> data = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
                if (data.containsKey("nodeId") && data.get("nodeId") != null) {
                    nodeCode = data.get("nodeId").toString();
                } else if (data.containsKey("nodeCode") && data.get("nodeCode") != null) {
                    nodeCode = data.get("nodeCode").toString();
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
                edgeNodeRepository.findByNodeCode(finalNodeCode).ifPresent(node -> {
                    node.setStatus(com.hesta.backend.enums.EdgeNodeStatus.ONLINE);
                    edgeNodeRepository.save(node);
                    log.info("EdgeNode [{}] registered and set to ONLINE", finalNodeCode);
                });
            }
        } catch (Exception e) {
            log.error("Failed to process EdgeNode registration for topic: {}", topic, e);
        }
    }

    private void handleDeviceStatus(String topic, String payload) {
        log.info("Processing status. Topic: [{}], Status: [{}]", topic, payload);
        try {
            String[] parts = topic.split("/");
            if (parts.length == 4 && "nodes".equals(parts[1]) && "status".equals(parts[3])) {
                String nodeCode = parts[2];
                String statusStr = payload != null ? payload.trim().replace("\"", "").toUpperCase() : "ONLINE";
                com.hesta.backend.enums.EdgeNodeStatus nodeStatus = "ONLINE".equals(statusStr)
                        ? com.hesta.backend.enums.EdgeNodeStatus.ONLINE
                        : com.hesta.backend.enums.EdgeNodeStatus.OFFLINE;
                edgeNodeRepository.findByNodeCode(nodeCode).ifPresent(n -> {
                    n.setStatus(nodeStatus);
                    edgeNodeRepository.save(n);
                    log.info("Updated node {} status to {}", nodeCode, nodeStatus);
                });
                return;
            }

            String deviceIdStr = extractDeviceIdFromTopic(topic);
            String nodeCode = extractNodeCodeFromTopic(topic);
            if (deviceIdStr == null) return;

            String statusStr = payload != null ? payload.trim().replace("\"", "").toUpperCase() : "ONLINE";
            com.hesta.backend.enums.DeviceStatus devStatus = "ONLINE".equals(statusStr)
                    ? com.hesta.backend.enums.DeviceStatus.ONLINE
                    : com.hesta.backend.enums.DeviceStatus.OFFLINE;

            java.util.Optional<com.hesta.backend.entity.Device> devOpt;
            try {
                devOpt = devices.findById(UUID.fromString(deviceIdStr));
            } catch (IllegalArgumentException e) {
                devOpt = devices.findByNodeCodeAndLocalId(nodeCode, deviceIdStr);
            }

            devOpt.ifPresent(device -> {
                device.setStatus(devStatus);
                device.setLastSeen(java.time.OffsetDateTime.now());
                devices.save(device);
                log.info("Updated device {} status to {}", device.getId(), devStatus);

                if (device.getNode() != null && devStatus == com.hesta.backend.enums.DeviceStatus.ONLINE) {
                    EdgeNode node = device.getNode();
                    if (node.getStatus() != com.hesta.backend.enums.EdgeNodeStatus.ONLINE) {
                        node.setStatus(com.hesta.backend.enums.EdgeNodeStatus.ONLINE);
                        edgeNodeRepository.save(node);
                    }
                }
            });
        } catch (Exception e) {
            log.error("Failed to process status from topic: {}", topic, e);
        }
    }
}


