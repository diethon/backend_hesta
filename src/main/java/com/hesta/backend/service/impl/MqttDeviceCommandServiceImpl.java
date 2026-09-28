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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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

    @org.springframework.beans.factory.annotation.Value("${mqtt.topic.prefix:hesta/nodes}")
    private String topicPrefix;

    @org.springframework.beans.factory.annotation.Value("${mqtt.command.timeout-ms:5000}")
    private long commandTimeoutMs;

    public static final ConcurrentHashMap<String, CompletableFuture<CommandResult>> pendingCommands = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, CompletableFuture<CommandResult>> activeDeviceCommands = new ConcurrentHashMap<>();




    @Override
    public CompletableFuture<CommandResult> sendCommand(UUID deviceId, DeviceAction action, Map<String, Object> parameters, StateChangeSource source) {
        CompletableFuture<CommandResult> existing = activeDeviceCommands.get(deviceId);
        if (existing != null && !existing.isDone()) {
            log.warn("Idempotency / Anti-spam: Device {} already has a pending command. Ignoring.", deviceId);
            return existing;
        }
        
        String commandId = UUID.randomUUID().toString();
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        activeDeviceCommands.put(deviceId, future);
        
        try {
            Device device = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> new RuntimeException("Device not found"));
            
            String nodeId = device.getNode() != null ? device.getNode().getId().toString() : "unknown";
            String topic = String.format("%s/%s/devices/%s/command", topicPrefix, nodeId, deviceId.toString());

            Map<String, Object> payload = new HashMap<>();
            payload.put("commandId", commandId);
            payload.put("action", action.name());
            payload.put("parameters", parameters == null ? new HashMap<>() : parameters);
            payload.put("timestamp", System.currentTimeMillis());
            
            String jsonPayload = objectMapper.writeValueAsString(payload);
            pendingCommands.put(commandId, future);
            
            log.info("Publishing MQTT Command to {}: {}", topic, jsonPayload);
            mqttGateway.sendToMqtt(topic, 1, jsonPayload);
            
            long metricStartTime = System.currentTimeMillis();
            return future.orTimeout(commandTimeoutMs, TimeUnit.MILLISECONDS)
                    .whenComplete((res, ex) -> {
                        activeDeviceCommands.remove(deviceId);
                        long roundtrip = System.currentTimeMillis() - metricStartTime;
                        if (ex == null) {
                            log.info("[METRICS] MQTT Roundtrip (Success) completed in {} ms for commandId {}", roundtrip, commandId);
                        }
                    })
                    .exceptionally(ex -> {
                        pendingCommands.remove(commandId);
                        long roundtrip = System.currentTimeMillis() - metricStartTime;
                        log.warn("[METRICS] MQTT Roundtrip (Timeout) after {} ms for commandId {}. Device {} timed out.", roundtrip, commandId, deviceId);
                        return CommandResult.builder()
                                .commandId(commandId)
                                .success(false)
                                .status("TIMEOUT")
                                .errorCode("TIMEOUT_NO_ACK")
                                .message("Device did not acknowledge within " + commandTimeoutMs + " ms")
                                .build();
                    });

        } catch (Exception e) {
            activeDeviceCommands.remove(deviceId);
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

    @Override
    public CompletableFuture<List<CommandResult>> sendRoomCommand(UUID roomId, DeviceAction action, Map<String, Object> parameters, StateChangeSource source) {
        List<Device> devices = deviceRepository.findByRoomId(roomId);
        if (devices.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        
        List<CompletableFuture<CommandResult>> futures = new ArrayList<>();
        for (Device d : devices) {
            futures.add(sendCommand(d.getId(), action, parameters, source));
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }
}