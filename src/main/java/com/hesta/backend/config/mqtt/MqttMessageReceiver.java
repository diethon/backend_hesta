package com.hesta.backend.config.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.service.DeviceService;
import com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class MqttMessageReceiver {

    private final DeviceService deviceService;
    private final ObjectMapper objectMapper;

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handleMessage(Message<?> message) {
        String topic = message.getHeaders().get(org.springframework.integration.mqtt.support.MqttHeaders.RECEIVED_TOPIC).toString();
        String payload = message.getPayload().toString();
        log.info("Received MQTT message. Topic: [{}], Payload: [{}]", topic, payload);
        
        try {
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            
            if (topic.endsWith("/state")) {
                String deviceIdStr = data.get("deviceId") != null ? data.get("deviceId").toString() : null;
                if (data.containsKey("state") && deviceIdStr != null) {
                    Map<String, Object> state = (Map<String, Object>) data.get("state");
                    deviceService.updateDeviceStateFromMqtt(deviceIdStr, state);
                }
            } else if (topic.endsWith("/ack")) {
                String commandId = data.get("commandId") != null ? data.get("commandId").toString() : null;
                if (commandId != null && MqttDeviceCommandServiceImpl.pendingCommands.containsKey(commandId)) {
                    CompletableFuture<CommandResult> future = MqttDeviceCommandServiceImpl.pendingCommands.remove(commandId);
                    
                    boolean success = "SUCCESS".equalsIgnoreCase(String.valueOf(data.get("status")));
                    
                    CommandResult result = CommandResult.builder()
                        .commandId(commandId)
                        .success(success)
                        .status(String.valueOf(data.get("status")))
                        .errorCode(data.containsKey("errorCode") ? String.valueOf(data.get("errorCode")) : null)
                        .message("Received ACK")
                        .build();
                    
                    future.complete(result);
                }
            }
        } catch (Exception e) {
            log.error("Error processing MQTT message", e);
        }
    }
}
