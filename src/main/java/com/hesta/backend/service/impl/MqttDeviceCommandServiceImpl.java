package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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




    @Transactional(readOnly = true)
    @Override
    public CompletableFuture<CommandResult> sendCommand(UUID deviceId, String action, Map<String, Object> parameters, StateChangeSource source) {
        CompletableFuture<CommandResult> existing = activeDeviceCommands.get(deviceId);
        if (existing != null && !existing.isDone()) {
            log.warn("Idempotency / Anti-spam: Device {} already has a pending command. Ignoring.", deviceId);
            return existing;
        }
        
        String commandId = UUID.randomUUID().toString();
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        activeDeviceCommands.put(deviceId, future);
        
        try {
            Device device = deviceRepository.findByIdWithNode(deviceId)
                    .orElse(null);
            
            String nodeCode = "unknown";
            String nodeIdStr = null;
            String localId = deviceId.toString();

            if (device != null) {
                try {
                    if (device.getNode() != null) {
                        nodeCode = device.getNode().getNodeCode();
                        if (device.getNode().getId() != null) {
                            nodeIdStr = device.getNode().getId().toString();
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Could not lazily load node for device {}: {}", deviceId, ex.getMessage());
                }
                if ((nodeCode == null || "unknown".equalsIgnoreCase(nodeCode)) && parameters != null) {
                    if (parameters.containsKey("nodeId") && parameters.get("nodeId") != null) {
                        nodeCode = parameters.get("nodeId").toString();
                        nodeIdStr = nodeCode;
                    } else if (parameters.containsKey("nodeCode") && parameters.get("nodeCode") != null) {
                        nodeCode = parameters.get("nodeCode").toString();
                        nodeIdStr = nodeCode;
                    }
                }
                String dLocalId = device.getLocalId();
                if (dLocalId != null && !dLocalId.isBlank()) {
                    localId = dLocalId;
                } else if (device.getMqttTopic() != null && !device.getMqttTopic().isBlank()) {
                    String[] parts = device.getMqttTopic().split("/");
                    for (int i = 0; i < parts.length - 1; i++) {
                        if ("device".equals(parts[i]) || "devices".equals(parts[i])) {
                            localId = parts[i + 1];
                            break;
                        }
                    }
                }
            } else {
                log.warn("Device {} not found in database. Checking parameters for IoT routing.", deviceId);
                if (parameters != null && parameters.containsKey("nodeId") && parameters.get("nodeId") != null) {
                    nodeCode = parameters.get("nodeId").toString();
                    nodeIdStr = nodeCode;
                } else if (parameters != null && parameters.containsKey("nodeCode") && parameters.get("nodeCode") != null) {
                    nodeCode = parameters.get("nodeCode").toString();
                    nodeIdStr = nodeCode;
                } else {
                    throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
                }
            }

            java.util.Set<String> targetTopics = new java.util.LinkedHashSet<>();
            if (nodeCode != null && !"unknown".equalsIgnoreCase(nodeCode)) {
                targetTopics.add(String.format("%s/%s/devices/%s/command", topicPrefix, nodeCode, localId));
                if (nodeIdStr != null && !nodeIdStr.equalsIgnoreCase(nodeCode)) {
                    targetTopics.add(String.format("%s/%s/devices/%s/command", topicPrefix, nodeIdStr, localId));
                }
            }
            if (device != null) {
                if (nodeCode != null && !"unknown".equalsIgnoreCase(nodeCode) && device.getId() != null && !device.getId().toString().equals(localId)) {
                    targetTopics.add(String.format("%s/%s/devices/%s/command", topicPrefix, nodeCode, device.getId()));
                    if (nodeIdStr != null && !nodeIdStr.equalsIgnoreCase(nodeCode)) {
                        targetTopics.add(String.format("%s/%s/devices/%s/command", topicPrefix, nodeIdStr, device.getId()));
                    }
                }
                if (device.getMqttTopic() != null && !device.getMqttTopic().isBlank()) {
                    targetTopics.add(device.getMqttTopic());
                    if (!device.getMqttTopic().endsWith("/command")) {
                        targetTopics.add(device.getMqttTopic() + "/command");
                    }
                }
            }

            // Normalize action and parameters for devices (including Air Conditioner and Gate)
            String normalizedAction = normalizeAction(action, parameters);
            boolean isGateOrDoor = (device != null && device.getDeviceType() != null &&
                    ("GATE".equalsIgnoreCase(device.getDeviceType()) || "ROLLING_DOOR".equalsIgnoreCase(device.getDeviceType())))
                    || "OPEN".equalsIgnoreCase(normalizedAction)
                    || "CLOSE".equalsIgnoreCase(normalizedAction)
                    || "STOP".equalsIgnoreCase(normalizedAction);

            Map<String, Object> effectiveParams = parameters == null ? new HashMap<>() : new HashMap<>(parameters);

            Map<String, Object> payload = new HashMap<>();
            if (isGateOrDoor) {
                // Keep payload compact so PubSubClient on ESP32 does not overflow its 128-byte buffer
                payload.put("action", normalizedAction);
            } else {
                payload.put("commandId", commandId);
                payload.put("deviceId", device != null ? device.getId().toString() : deviceId.toString());
                payload.put("target", localId);
                payload.put("action", normalizedAction);

                // Put parameters into nested parameters map for backend contract
                payload.put("parameters", effectiveParams);

                // Flatten parameters to the root level of JSON so ESP32 (doc["power"], doc["temperature"], doc["fan"], etc.) can parse directly
                for (Map.Entry<String, Object> entry : effectiveParams.entrySet()) {
                    if (!payload.containsKey(entry.getKey())) {
                        payload.put(entry.getKey(), entry.getValue());
                    }
                }

                // Normalization helpers for AC fields
                applyAcPayloadConversions(normalizedAction, payload, effectiveParams);

                payload.put("timestamp", System.currentTimeMillis());
            }
            
            // Cập nhật trạng thái vào database nếu device tồn tại
            if (device != null) {
                updateDeviceState(device, normalizedAction, effectiveParams);
            }
            
            String jsonPayload = objectMapper.writeValueAsString(payload);
            pendingCommands.put(commandId, future);
            
            for (String targetTopic : targetTopics) {
                log.info("Publishing MQTT Command to {}: {}", targetTopic, jsonPayload);
                mqttGateway.sendToMqtt(targetTopic, 1, jsonPayload);
            }
            
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

    public static void completeDeviceCommand(UUID deviceId, Map<String, Object> state) {
        CompletableFuture<CommandResult> future = activeDeviceCommands.remove(deviceId);
        if (future != null && !future.isDone()) {
            pendingCommands.values().remove(future);
            future.complete(CommandResult.builder()
                    .commandId(UUID.randomUUID().toString())
                    .success(true)
                    .status("SUCCESS")
                    .acknowledgedState(state)
                    .message("Device acknowledged via state telemetry")
                    .build());
        }
    }

    private String normalizeAction(String action, Map<String, Object> parameters) {
        if (action == null) return "UNKNOWN";
        String act = action.trim().toUpperCase();
        switch (act) {
            case "TURN_ON":
            case "POWER_ON":
                return "SET_POWER";
            case "TURN_OFF":
            case "POWER_OFF":
                return "SET_POWER";
            case "TEMP_UP":
                return "TEMPERATURE_PLUS";
            case "TEMP_DOWN":
                return "TEMPERATURE_MINUS";
            case "GATE_OPEN":
            case "OPEN":
                return "OPEN";
            case "GATE_CLOSE":
            case "CLOSE":
                return "CLOSE";
            case "GATE_STOP":
            case "STOP":
                return "STOP";
            default:
                return act;
        }
    }

    private void applyAcPayloadConversions(String action, Map<String, Object> payload, Map<String, Object> parameters) {
        if ("SET_POWER".equals(action)) {
            if (parameters.containsKey("power")) {
                Object p = parameters.get("power");
                boolean boolVal = Boolean.TRUE.equals(p) || "true".equalsIgnoreCase(String.valueOf(p)) || "on".equalsIgnoreCase(String.valueOf(p)) || "1".equals(String.valueOf(p));
                payload.put("power", boolVal);
            }
        }
        if (parameters.containsKey("temp") && !parameters.containsKey("temperature")) {
            payload.put("temperature", parameters.get("temp"));
        }
        if (parameters.containsKey("speed") && !parameters.containsKey("fan")) {
            payload.put("fan", parameters.get("speed"));
        }
        if (payload.containsKey("fan") && payload.get("fan") != null) {
            payload.put("fan", payload.get("fan").toString().toUpperCase());
        }
        if (payload.containsKey("mode") && payload.get("mode") != null) {
            payload.put("mode", payload.get("mode").toString().toUpperCase());
        }
    }

    @Override
    public CompletableFuture<List<CommandResult>> sendRoomCommand(UUID roomId, String action, Map<String, Object> parameters, StateChangeSource source) {
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

    private void updateDeviceState(Device device, String action, Map<String, Object> parameters) {
        try {
            Map<String, Object> currentState = device.getCurrentState() != null ? new HashMap<>(device.getCurrentState()) : new HashMap<>();
            String act = action != null ? action.trim().toUpperCase() : "";

            switch (act) {
                case "POWER_ON":
                case "TURN_ON":
                    currentState.put("power", "ON");
                    break;
                case "POWER_OFF":
                case "TURN_OFF":
                    currentState.put("power", "OFF");
                    break;
                case "SET_POWER":
                    if (parameters != null && parameters.containsKey("power")) {
                        Object p = parameters.get("power");
                        boolean pBool = Boolean.TRUE.equals(p) || "true".equalsIgnoreCase(String.valueOf(p)) || "on".equalsIgnoreCase(String.valueOf(p)) || "1".equals(String.valueOf(p));
                        currentState.put("power", pBool);
                    }
                    break;
                case "SET_TEMPERATURE":
                    if (parameters != null && (parameters.containsKey("temperature") || parameters.containsKey("temp"))) {
                        Object t = parameters.containsKey("temperature") ? parameters.get("temperature") : parameters.get("temp");
                        try {
                            currentState.put("temperature", Integer.parseInt(t.toString()));
                        } catch (Exception ignored) {
                            currentState.put("temperature", t);
                        }
                    }
                    break;
                case "TEMPERATURE_PLUS":
                    Object curTempPlus = currentState.get("temperature");
                    int tPlus = 26;
                    try {
                        if (curTempPlus != null) tPlus = Integer.parseInt(curTempPlus.toString());
                    } catch (Exception ignored) {}
                    if (tPlus < 30) currentState.put("temperature", tPlus + 1);
                    break;
                case "TEMPERATURE_MINUS":
                    Object curTempMinus = currentState.get("temperature");
                    int tMinus = 26;
                    try {
                        if (curTempMinus != null) tMinus = Integer.parseInt(curTempMinus.toString());
                    } catch (Exception ignored) {}
                    if (tMinus > 20) currentState.put("temperature", tMinus - 1);
                    break;
                case "SET_FAN":
                    if (parameters != null && (parameters.containsKey("fan") || parameters.containsKey("speed"))) {
                        Object f = parameters.containsKey("fan") ? parameters.get("fan") : parameters.get("speed");
                        currentState.put("fan", f != null ? f.toString().toUpperCase() : "AUTO");
                    }
                    break;
                case "SET_MODE":
                    if (parameters != null && parameters.containsKey("mode")) {
                        Object m = parameters.get("mode");
                        currentState.put("mode", m != null ? m.toString().toUpperCase() : "COOL");
                    }
                    break;
                case "SET_SWING":
                    if (parameters != null && parameters.containsKey("swing")) {
                        Object s = parameters.get("swing");
                        currentState.put("swing", Boolean.TRUE.equals(s) || "true".equalsIgnoreCase(String.valueOf(s)) || "on".equalsIgnoreCase(String.valueOf(s)));
                    }
                    break;
                case "SET_TIMER":
                    if (parameters != null) {
                        currentState.put("timerEnabled", true);
                        if (parameters.containsKey("hour")) currentState.put("timerHour", parameters.get("hour"));
                        if (parameters.containsKey("halfHour")) currentState.put("timerHalfHour", parameters.get("halfHour"));
                    }
                    break;
                case "CANCEL_TIMER":
                    currentState.put("timerEnabled", false);
                    currentState.put("timerHour", 0);
                    currentState.put("timerHalfHour", false);
                    break;
                case "SET_RGB":
                case "SET_COLOR":
                    currentState.put("power", "ON");
                    if (parameters != null) {
                        if (parameters.containsKey("r")) currentState.put("r", parameters.get("r"));
                        if (parameters.containsKey("g")) currentState.put("g", parameters.get("g"));
                        if (parameters.containsKey("b")) currentState.put("b", parameters.get("b"));
                    }
                    break;
                case "SET_BRIGHTNESS":
                    if (parameters != null) {
                        Object br = parameters.containsKey("brightness") ? parameters.get("brightness") : parameters.get("level");
                        if (br != null) {
                            currentState.put("brightness", br);
                            try {
                                if (Integer.parseInt(br.toString()) > 0) {
                                    currentState.put("power", "ON");
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    break;
                case "OPEN":
                    currentState.put("state", "OPENING");
                    break;
                case "CLOSE":
                    currentState.put("state", "CLOSING");
                    break;
                case "STOP":
                    currentState.put("state", "STOPPED");
                    break;
                default:
                    if (parameters != null && !parameters.isEmpty()) {
                        currentState.putAll(parameters);
                    }
                    break;
            }

            boolean isGateOrDoor = device.getDeviceType() != null &&
                    ("GATE".equalsIgnoreCase(device.getDeviceType()) || "ROLLING_DOOR".equalsIgnoreCase(device.getDeviceType()));
            if (isGateOrDoor) {
                Object stateVal = currentState.get("state");
                currentState.clear();
                if (stateVal != null) {
                    currentState.put("state", stateVal);
                }
            }

            device.setCurrentState(currentState);
            deviceRepository.save(device);
            log.info("Updated device {} currentState in DB: {}", device.getId(), currentState);
        } catch (Exception e) {
            log.error("Failed to update currentState for device {}", device.getId(), e);
        }
    }
}