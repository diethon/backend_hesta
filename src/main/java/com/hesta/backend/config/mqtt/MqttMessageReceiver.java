package com.hesta.backend.config.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.TelemetryPayload;
import com.hesta.backend.service.DeviceService;
import com.hesta.backend.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MqttMessageReceiver {

    private final ObjectMapper objectMapper;
    private final TelemetryService telemetryService;
    private final DeviceService deviceService;

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handleMessage(Message<?> message) {
        Object topicHeader = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
        String topic = topicHeader != null ? topicHeader.toString() : "";
        String payload = message.getPayload().toString();

        log.info("Received MQTT message. Topic: [{}], Payload: [{}]", topic, payload);

        try {
            // 1. Telemetry topic: hesta/nodes/{nodeId}/devices/{deviceId}/telemetry
            if (topic.endsWith("/telemetry") || topic.endsWith("/sensor")) {
                TelemetryPayload telemetryPayload = objectMapper.readValue(payload, TelemetryPayload.class);
                telemetryService.processTelemetry(topic, telemetryPayload);
            }
            // 2. State topic: hesta/nodes/{nodeId}/devices/{deviceId}/state
            else if (topic.endsWith("/state")) {
                String[] parts = topic.split("/");
                // Format: hesta/nodes/{nodeId}/devices/{deviceId}/state
                if (parts.length >= 5) {
                    String deviceId = parts[3];
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stateMap = objectMapper.readValue(payload, Map.class);
                    deviceService.updateDeviceStateFromMqtt(deviceId, stateMap);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse or process MQTT message on topic [{}]: {}", topic, e.getMessage(), e);
        }
    }
}