package com.hesta.backend.config.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.enums.DeviceType;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.AutomationEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MqttMessageReceiver {
    private final DeviceRepository devices;
    private final AutomationEngine automationEngine;
    private final ObjectMapper objectMapper;

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handleMessage(Message<?> message) {
        Object receivedTopic = message.getHeaders().get(org.springframework.integration.mqtt.support.MqttHeaders.RECEIVED_TOPIC);
        if (!(receivedTopic instanceof String topic)) return;
        String[] parts = topic.split("/");
        if (parts.length != 6 || !"hesta".equals(parts[0]) || !"nodes".equals(parts[1])
                || !"devices".equals(parts[3]) || !"sensor".equals(parts[5])) return;
        try {
            UUID deviceId = UUID.fromString(parts[4]);
            var device = devices.findById(deviceId).orElse(null);
            if (device == null || device.getDeviceType() != DeviceType.SENSOR || device.getNode() == null
                    || !parts[2].equals(device.getNode().getId().toString())) return;
            Map<String, Object> data = objectMapper.readValue(message.getPayload().toString(), new TypeReference<>() {});
            automationEngine.process(device.getHome().getId(), AutomationEventRequest.builder()
                    .sourceDeviceId(deviceId).eventType("SENSOR_READING").data(data).build());
        } catch (Exception exception) {
            log.warn("Ignored invalid sensor message on topic {}", topic);
        }
    }
}
