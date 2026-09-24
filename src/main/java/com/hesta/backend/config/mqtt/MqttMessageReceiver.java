package com.hesta.backend.config.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.TelemetryPayload;
import com.hesta.backend.service.DeviceService;
import com.hesta.backend.service.TelemetryService;
import com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class MqttMessageReceiver {

    private final ObjectMapper objectMapper;
    private final TelemetryService telemetryService;
    private final DeviceService deviceService;

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

            if (topic.endsWith("/telemetry")
                    || topic.endsWith("/sensor")) {

                handleTelemetry(topic, payload);

            }

            // =====================================================
            // 4. DEVICE STATE
            // =====================================================

            else if (topic.endsWith("/state")) {

                handleDeviceState(topic, payload);

            }

            // =====================================================
            // 5. COMMAND ACK
            // =====================================================

            else if (topic.endsWith("/ack")) {

                handleCommandAck(payload);

            }

            // =====================================================
            // 6. Unknown topic
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

        TelemetryPayload telemetryPayload =
                objectMapper.readValue(
                        payload,
                        TelemetryPayload.class
                );

        telemetryService.processTelemetry(
                topic,
                telemetryPayload
        );

        log.debug(
                "Telemetry processed successfully. Topic: [{}]",
                topic
        );
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

            deviceService.updateDeviceStateFromMqtt(
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

                deviceService.updateDeviceStateFromMqtt(
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

        deviceService.updateDeviceStateFromMqtt(
                deviceId,
                state
        );
    }


    // =============================================================
    // EXTRACT DEVICE ID FROM TOPIC
    // =============================================================

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
}