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
import org.springframework.transaction.annotation.Transactional;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.diagnostics.MqttDiagnosticsService diagnosticsService;

    public static final ConcurrentHashMap<String, CompletableFuture<CommandResult>> pendingCommands = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, CompletableFuture<CommandResult>> activeDeviceCommands = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> commandStartTimes = new ConcurrentHashMap<>();

    @Transactional
    @Override
    public CompletableFuture<CommandResult> sendCommand(UUID deviceId, String action, Map<String, Object> parameters, StateChangeSource source) {
        CompletableFuture<CommandResult> existing = activeDeviceCommands.get(deviceId);
        Long lastStartTime = commandStartTimes.get(deviceId);
        long now = System.currentTimeMillis();

        if (existing != null && !existing.isDone()) {
            if (source == StateChangeSource.MANUAL) {
                if (lastStartTime != null && (now - lastStartTime < 2000)) {
                    log.warn("Idempotency / Anti-spam: Device {} click spam rejected (<2s).", deviceId);
                    return CompletableFuture.completedFuture(CommandResult.builder()
                            .commandId(UUID.randomUUID().toString())
                            .success(false)
                            .status("REJECTED")
                            .errorCode("COMMAND_IN_PROGRESS_DUPLICATE_REJECTED")
                            .message("Thiết bị đang thực thi lệnh trước, vui lòng không click liên tục")
                            .build());
                }
                log.warn("Idempotency / Anti-spam: Device {} already has a pending command. Ignoring.", deviceId);
                return existing;
            }
            return existing.handle((res, ex) -> null)
                    .thenCompose(ignored -> sendCommand(deviceId, action, parameters, source));
        }
        
        String commandId = UUID.randomUUID().toString();
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        activeDeviceCommands.put(deviceId, future);
        commandStartTimes.put(deviceId, now);
        
        String resolvedNodeCode = "unknown";
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
            resolvedNodeCode = nodeCode;

            // Circuit Breaker check
            if (diagnosticsService != null && nodeCode != null && !"unknown".equalsIgnoreCase(nodeCode)
                    && !diagnosticsService.allowExecution(nodeCode)) {
                log.warn("Circuit Breaker OPEN for node {}. Blocking command to device {}.", nodeCode, deviceId);
                activeDeviceCommands.remove(deviceId);
                commandStartTimes.remove(deviceId);
                return CompletableFuture.completedFuture(CommandResult.builder()
                        .commandId(commandId)
                        .success(false)
                        .status("FAILED")
                        .errorCode("CIRCUIT_BREAKER_OPEN")
                        .message("Mạch điều khiển Node đang ngắt bảo vệ (CIRCUIT OPEN)")
                        .build());
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

            Map<String, Object> effectiveParams = parameters == null ? new HashMap<>() : new HashMap<>(parameters);

            // Normalize action and parameters for devices (including Air Conditioner and Gate)
            String normalizedAction = normalizeAction(device, action, effectiveParams);
            boolean isGateOrDoor = (device != null && device.getDeviceType() != null &&
                    ("GATE".equalsIgnoreCase(device.getDeviceType()) || "ROLLING_DOOR".equalsIgnoreCase(device.getDeviceType())))
                    || "OPEN".equalsIgnoreCase(normalizedAction)
                    || "CLOSE".equalsIgnoreCase(normalizedAction)
                    || "STOP".equalsIgnoreCase(normalizedAction);

            if ("SET_BRIGHTNESS".equalsIgnoreCase(normalizedAction)) {
                Object br = effectiveParams.get("brightness");
                if (br == null) br = effectiveParams.get("level");
                if (br != null) {
                    try {
                        if (Integer.parseInt(br.toString()) > 0) {
                            effectiveParams.put("power", "ON");
                            if (!effectiveParams.containsKey("brightness")) {
                                effectiveParams.put("brightness", br);
                            }
                            if (!effectiveParams.containsKey("level")) {
                                effectiveParams.put("level", br);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            if ("SET_COLOR".equalsIgnoreCase(normalizedAction) || "SET_RGB".equalsIgnoreCase(normalizedAction)) {
                effectiveParams.put("power", "ON");
                if (!effectiveParams.containsKey("brightness")) {
                    effectiveParams.put("brightness", 100);
                }
                if (!effectiveParams.containsKey("r") || !effectiveParams.containsKey("g") || !effectiveParams.containsKey("b")) {
                    Object hexVal = effectiveParams.get("hex");
                    if (hexVal == null) hexVal = effectiveParams.get("color");
                    if (hexVal instanceof String hexStr && hexStr.startsWith("#") && hexStr.length() == 7) {
                        try {
                            effectiveParams.put("r", Integer.parseInt(hexStr.substring(1, 3), 16));
                            effectiveParams.put("g", Integer.parseInt(hexStr.substring(3, 5), 16));
                            effectiveParams.put("b", Integer.parseInt(hexStr.substring(5, 7), 16));
                        } catch (Exception ignored) {}
                    }
                }
            }
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
                if (diagnosticsService != null) {
                    diagnosticsService.recordPacket(targetTopic, "OUT", 1, jsonPayload);
                }
            }
            
            long metricStartTime = System.currentTimeMillis();
            final String targetNodeCode = nodeCode;
            return future.orTimeout(commandTimeoutMs, TimeUnit.MILLISECONDS)
                    .whenComplete((res, ex) -> {
                        activeDeviceCommands.remove(deviceId);
                        commandStartTimes.remove(deviceId);
                        long roundtrip = System.currentTimeMillis() - metricStartTime;
                        if (ex == null && res != null && res.isSuccess()) {
                            log.info("[METRICS] MQTT Roundtrip (Success) completed in {} ms for commandId {}", roundtrip, commandId);
                            res.setLatencyMs(roundtrip);
                            if (diagnosticsService != null && targetNodeCode != null && !"unknown".equalsIgnoreCase(targetNodeCode)) {
                                diagnosticsService.recordCommandSuccess(targetNodeCode, roundtrip);
                            }
                        } else {
                            if (diagnosticsService != null && targetNodeCode != null && !"unknown".equalsIgnoreCase(targetNodeCode)) {
                                diagnosticsService.recordCommandFailure(targetNodeCode);
                            }
                        }
                    })
                    .exceptionally(ex -> {
                        pendingCommands.remove(commandId);
                        activeDeviceCommands.remove(deviceId);
                        commandStartTimes.remove(deviceId);
                        long roundtrip = System.currentTimeMillis() - metricStartTime;
                        log.warn("[METRICS] MQTT Roundtrip (Timeout) after {} ms for commandId {}. Device {} timed out.", roundtrip, commandId, deviceId);
                        if (diagnosticsService != null && targetNodeCode != null && !"unknown".equalsIgnoreCase(targetNodeCode)) {
                            diagnosticsService.recordCommandFailure(targetNodeCode);
                        }
                        return CommandResult.builder()
                                .commandId(commandId)
                                .success(false)
                                .status("TIMEOUT")
                                .errorCode("TIMEOUT_NO_ACK")
                                .message("Device did not acknowledge within " + commandTimeoutMs + " ms")
                                .latencyMs(roundtrip)
                                .build();
                    });

        } catch (Exception e) {
            activeDeviceCommands.remove(deviceId);
            commandStartTimes.remove(deviceId);
            if (diagnosticsService != null && resolvedNodeCode != null && !"unknown".equalsIgnoreCase(resolvedNodeCode)) {
                diagnosticsService.recordCommandFailure(resolvedNodeCode);
            }
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
        commandStartTimes.remove(deviceId);
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

    private String normalizeAction(Device device, String action, Map<String, Object> parameters) {
        if (action == null) return "UNKNOWN";
        String act = action.trim().toUpperCase();

        boolean isAc = (device != null && device.getDeviceType() != null &&
                ("AIR_CONDITIONER".equalsIgnoreCase(device.getDeviceType()) || "AC".equalsIgnoreCase(device.getDeviceType())))
                || (parameters != null && (parameters.containsKey("temperature") || parameters.containsKey("temp") || parameters.containsKey("fan")));

        boolean isGateOrDoor = (device != null && device.getDeviceType() != null &&
                ("GATE".equalsIgnoreCase(device.getDeviceType()) || "ROLLING_DOOR".equalsIgnoreCase(device.getDeviceType())))
                || "OPEN".equalsIgnoreCase(act)
                || "CLOSE".equalsIgnoreCase(act)
                || "STOP".equalsIgnoreCase(act)
                || "GATE_OPEN".equalsIgnoreCase(act)
                || "GATE_CLOSE".equalsIgnoreCase(act)
                || "GATE_STOP".equalsIgnoreCase(act);

        if (isGateOrDoor) {
            switch (act) {
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

        if (isAc) {
            switch (act) {
                case "TURN_ON":
                case "POWER_ON":
                    if (parameters != null && !parameters.containsKey("power")) {
                        parameters.put("power", true);
                    }
                    return "SET_POWER";
                case "TURN_OFF":
                case "POWER_OFF":
                    if (parameters != null && !parameters.containsKey("power")) {
                        parameters.put("power", false);
                    }
                    return "SET_POWER";
                case "TEMP_UP":
                    return "TEMPERATURE_PLUS";
                case "TEMP_DOWN":
                    return "TEMPERATURE_MINUS";
                default:
                    return act;
            }
        }

        // Default: LED, RGB Light, Relay, Switch and other generic IoT devices
        switch (act) {
            case "TURN_ON":
                return "TURN_ON";
            case "TURN_OFF":
                return "TURN_OFF";
            case "POWER_ON":
                return "POWER_ON";
            case "POWER_OFF":
                return "POWER_OFF";
            case "SET_POWER":
                if (parameters != null && parameters.containsKey("power")) {
                    Object p = parameters.get("power");
                    boolean boolVal = Boolean.TRUE.equals(p) || "true".equalsIgnoreCase(String.valueOf(p)) || "on".equalsIgnoreCase(String.valueOf(p)) || "1".equals(String.valueOf(p));
                    return boolVal ? "POWER_ON" : "POWER_OFF";
                }
                return "POWER_ON";
            case "TEMP_UP":
            case "INCREASE_TEMPERATURE":
                return "TEMPERATURE_PLUS";
            case "TEMP_DOWN":
            case "DECREASE_TEMPERATURE":
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
            case "SET_COLOR":
            case "SET_RGB":
                if (device != null && device.getCapabilities() != null) {
                    boolean hasRgb = device.getCapabilities().values().stream()
                            .flatMap(List::stream).anyMatch("SET_RGB"::equalsIgnoreCase);
                    boolean hasColor = device.getCapabilities().values().stream()
                            .flatMap(List::stream).anyMatch("SET_COLOR"::equalsIgnoreCase);
                    if (hasRgb && !hasColor) return "SET_RGB";
                    if (hasColor && !hasRgb) return "SET_COLOR";
                }
                return act;
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
                    int amtPlus = 1;
                    if (parameters != null && parameters.containsKey("amount")) {
                        try { amtPlus = Integer.parseInt(parameters.get("amount").toString()); } catch (Exception ignored) {}
                    }
                    if (tPlus + amtPlus <= 30) currentState.put("temperature", tPlus + amtPlus);
                    else currentState.put("temperature", 30);
                    break;
                case "TEMPERATURE_MINUS":
                    Object curTempMinus = currentState.get("temperature");
                    int tMinus = 26;
                    try {
                        if (curTempMinus != null) tMinus = Integer.parseInt(curTempMinus.toString());
                    } catch (Exception ignored) {}
                    int amtMinus = 1;
                    if (parameters != null && parameters.containsKey("amount")) {
                        try { amtMinus = Integer.parseInt(parameters.get("amount").toString()); } catch (Exception ignored) {}
                    }
                    if (tMinus - amtMinus >= 16) currentState.put("temperature", tMinus - amtMinus);
                    else currentState.put("temperature", 16);
                    break;
                case "INCREASE_BRIGHTNESS":
                    Object curBrInc = currentState.get("brightness");
                    int brInc = 50;
                    try {
                        if (curBrInc != null) brInc = Integer.parseInt(curBrInc.toString());
                    } catch (Exception ignored) {}
                    int amtBrInc = 20;
                    if (parameters != null && parameters.containsKey("amount")) {
                        try { amtBrInc = Integer.parseInt(parameters.get("amount").toString()); } catch (Exception ignored) {}
                    }
                    if (brInc + amtBrInc <= 100) currentState.put("brightness", brInc + amtBrInc);
                    else currentState.put("brightness", 100);
                    break;
                case "DECREASE_BRIGHTNESS":
                    Object curBrDec = currentState.get("brightness");
                    int brDec = 50;
                    try {
                        if (curBrDec != null) brDec = Integer.parseInt(curBrDec.toString());
                    } catch (Exception ignored) {}
                    int amtBrDec = 20;
                    if (parameters != null && parameters.containsKey("amount")) {
                        try { amtBrDec = Integer.parseInt(parameters.get("amount").toString()); } catch (Exception ignored) {}
                    }
                    if (brDec - amtBrDec >= 0) currentState.put("brightness", brDec - amtBrDec);
                    else currentState.put("brightness", 0);
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
                        if (parameters.containsKey("brightness")) currentState.put("brightness", parameters.get("brightness"));
                        if (parameters.containsKey("color")) currentState.put("color", parameters.get("color"));
                        else if (parameters.containsKey("hex")) currentState.put("color", parameters.get("hex"));
                        else if (parameters.containsKey("r") && parameters.containsKey("g") && parameters.containsKey("b")) {
                            currentState.put("color", Map.of(
                                "r", parameters.get("r"),
                                "g", parameters.get("g"),
                                "b", parameters.get("b")
                            ));
                        }
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