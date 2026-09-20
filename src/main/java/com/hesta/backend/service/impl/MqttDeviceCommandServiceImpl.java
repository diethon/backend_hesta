package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class MqttDeviceCommandServiceImpl implements DeviceCommandService {

    private final MqttGateway mqttGateway;
    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;

    public static final ConcurrentHashMap<String, CompletableFuture<CommandResult>> pendingCommands = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<CommandResult> sendCommand(UUID deviceId, DeviceAction action, Map<String, Object> parameters, StateChangeSource source) {
        String commandId = UUID.randomUUID().toString();
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        
        try {
            Device device = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> new RuntimeException("Device not found"));
            
            String nodeId = device.getNode() != null ? device.getNode().getId().toString() : "unknown";
            String topic = String.format("hesta/nodes/%s/devices/%s/command", nodeId, deviceId.toString());

            Map<String, Object> payload = new HashMap<>();
            payload.put("commandId", commandId);
            payload.put("action", action.name());
            payload.put("parameters", parameters == null ? new HashMap<>() : parameters);
            payload.put("timestamp", System.currentTimeMillis());
            
            String jsonPayload = objectMapper.writeValueAsString(payload);
            pendingCommands.put(commandId, future);
            
            log.info("Publishing MQTT Command to {}: {}", topic, jsonPayload);
            mqttGateway.sendToMqtt(topic, 1, jsonPayload);
            
            return future.orTimeout(5, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        pendingCommands.remove(commandId);
                        log.warn("Command {} to device {} timed out", commandId, deviceId);
                        return CommandResult.builder()
                                .commandId(commandId)
                                .success(false)
                                .status("TIMEOUT")
                                .errorCode("TIMEOUT_NO_ACK")
                                .message("Device did not acknowledge within 5 seconds")
                                .build();
                    });

        } catch (Exception e) {
            log.error("Failed to send command to device {}", deviceId, e);
            future.complete(CommandResult.builder()
                    .commandId(commandId)
                    .success(false)
                    .status("FAILED")
                    .errorCode("INTERNAL_ERROR")
                    .message(e.getMessage())
                    .build());
        }
        
        return future;
    }
}
