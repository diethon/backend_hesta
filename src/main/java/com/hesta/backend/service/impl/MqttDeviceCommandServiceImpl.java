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
import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.dto.response.TwinDeviceSnapshotResponse;
import com.hesta.backend.enums.TwinHealthStatus;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ApplicationEventPublisher applicationEventPublisher;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TwinSnapshotMapper twinSnapshotMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RealtimeEventPublisher realtimeEventPublisher;

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
        Long lastStartTime = commandStartTimes.get(deviceId);
        long now = System.currentTimeMillis();

        // 1. Anti-spam click window: Chặn click spam (RGB/Dimming 60ms để kéo mượt, Relay nguồn 400ms)
        boolean isColorOrDimmer = "SET_RGB".equalsIgnoreCase(action)
                || "SET_BRIGHTNESS".equalsIgnoreCase(action)
                || "SET_COLOR".equalsIgnoreCase(action);
        long antiSpamWindowMs = isColorOrDimmer ? 60L : 400L;
        if (lastStartTime != null && (now - lastStartTime < antiSpamWindowMs)) {
            if (!isColorOrDimmer && source != StateChangeSource.VOICE) {
                log.warn("Idempotency / Anti-spam: Device {} click spam rejected ({} ms < {} ms).", deviceId, now - lastStartTime, antiSpamWindowMs);
                return CompletableFuture.completedFuture(CommandResult.builder()
                        .commandId(UUID.randomUUID().toString())
                        .success(false)
                        .status("REJECTED")
                        .errorCode("COMMAND_IN_PROGRESS_DUPLICATE_REJECTED")
                        .message("Thiết bị đang thực thi lệnh trước, vui lòng không click liên tục")
                        .build());
            }
        }

        // 2. Pending execution: Nếu lệnh trước chưa hoàn tất (đang đợi ACK)
        CompletableFuture<CommandResult> existing = activeDeviceCommands.get(deviceId);
        if (existing != null && !existing.isDone()) {
            if (isColorOrDimmer || source == StateChangeSource.VOICE) {
                // Continuous adjustment or Voice direct override: Lệnh mới nhất sẽ ghi đè (supersede) lệnh trước để phản hồi tức thì
                log.info("Idempotency: Superseding previous in-flight command for device {} with new {} command (source: {})", deviceId, action, source);
                existing.complete(CommandResult.builder()
                        .commandId(UUID.randomUUID().toString())
                        .success(true)
                        .status("SUPERSEDED")
                        .message("Superseded by newer stream or voice command")
                        .build());
            } else if (source != StateChangeSource.MANUAL) {
                // Khi chạy kịch bản Automation / Scene: nối chuỗi thực thi sau khi lệnh trước xong
                log.info("Automation / Scene chaining command for device {}", deviceId);
                return existing.handle((res, ex) -> null)
                        .thenCompose(ignored -> sendCommand(deviceId, action, parameters, source));
            } else {
                log.warn("Idempotency / Anti-spam: Device {} already has a pending command in flight. Rejecting duplicate.", deviceId);
                return CompletableFuture.completedFuture(CommandResult.builder()
                        .commandId(UUID.randomUUID().toString())
                        .success(false)
                        .status("REJECTED")
                        .errorCode("COMMAND_IN_PROGRESS_DUPLICATE_REJECTED")
                        .message("Thiết bị đang thực thi lệnh trước, vui lòng không click liên tục")
                        .build());
            }
        }

        // 3. Nguyên tử hóa việc chiếm giữ Lock (Atomic Lock Acquisition chống Race Condition)
        String commandId = UUID.randomUUID().toString();
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        if (isColorOrDimmer || source == StateChangeSource.VOICE) {
            activeDeviceCommands.put(deviceId, future);
        } else {
            CompletableFuture<CommandResult> previous = activeDeviceCommands.putIfAbsent(deviceId, future);
            if (previous != null && previous.isDone()) {
                activeDeviceCommands.put(deviceId, future);
            }
            if (previous != null && !previous.isDone()) {
                log.warn("Idempotency: Concurrent race collision on device {}. Rejecting duplicate.", deviceId);
                return CompletableFuture.completedFuture(CommandResult.builder()
                        .commandId(commandId)
                        .success(false)
                        .status("REJECTED")
                        .errorCode("COMMAND_IN_PROGRESS_DUPLICATE_REJECTED")
                        .message("Thiết bị đang thực thi lệnh trước, vui lòng không click liên tục")
                        .build());
            }
        }
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
                activeDeviceCommands.remove(deviceId, future);
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
            // Strictly target canonical hardware topic matching ESP32 firmware: hesta/nodes/{nodeId}/devices/{deviceId}/command
            String routingNode = (nodeIdStr != null && !nodeIdStr.isBlank()) ? nodeIdStr : nodeCode;
            String routingDevice = deviceId.toString();

            if (routingNode != null && !"unknown".equalsIgnoreCase(routingNode)) {
                targetTopics.add(String.format("%s/%s/devices/%s/command", topicPrefix, routingNode, routingDevice));
            } else if (device != null && device.getMqttTopic() != null && !device.getMqttTopic().isBlank()) {
                String devTopic = device.getMqttTopic().trim();
                if (devTopic.endsWith("/state") || devTopic.endsWith("/telemetry") || devTopic.endsWith("/status")) {
                    devTopic = devTopic.substring(0, devTopic.lastIndexOf('/')) + "/command";
                } else if (!devTopic.endsWith("/command")) {
                    devTopic = devTopic + "/command";
                }
                targetTopics.add(devTopic);
            } else {
                targetTopics.add(String.format("%s/unknown/devices/%s/command", topicPrefix, routingDevice));
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
                        int brInt = Math.max(0, Math.min(100, Integer.parseInt(br.toString())));
                        effectiveParams.put("brightness", brInt);
                        effectiveParams.put("level", brInt);
                        if (brInt > 0) {
                            effectiveParams.put("power", "ON");
                        }
                    } catch (Exception ignored) {}
                }
            }
            if ("SET_RGB".equalsIgnoreCase(normalizedAction) || "SET_COLOR".equalsIgnoreCase(normalizedAction)) {
                effectiveParams.put("power", "ON");
                if (!effectiveParams.containsKey("brightness")) {
                    effectiveParams.put("brightness", 100);
                }

                // 1. Phân tích chuỗi Hex nếu truyền dạng #RRGGBB
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

                // 2. Trích xuất từ color map object
                if (effectiveParams.get("color") instanceof Map<?, ?> colorMapParam) {
                    if (!effectiveParams.containsKey("r") && colorMapParam.containsKey("r")) {
                        effectiveParams.put("r", colorMapParam.get("r"));
                    }
                    if (!effectiveParams.containsKey("g") && colorMapParam.containsKey("g")) {
                        effectiveParams.put("g", colorMapParam.get("g"));
                    }
                    if (!effectiveParams.containsKey("b") && colorMapParam.containsKey("b")) {
                        effectiveParams.put("b", colorMapParam.get("b"));
                    }
                }

                int defaultR = 0;
                int defaultG = 0;
                int defaultB = 0;
                if (device != null && device.getCurrentState() != null) {
                    Map<String, Object> cur = device.getCurrentState();
                    defaultR = clampRgb(cur.get("r"), 0);
                    defaultG = clampRgb(cur.get("g"), 0);
                    defaultB = clampRgb(cur.get("b"), 0);
                    if (cur.get("color") instanceof Map<?, ?> cMap) {
                        if (cur.get("r") == null && cMap.containsKey("r")) defaultR = clampRgb(cMap.get("r"), defaultR);
                        if (cur.get("g") == null && cMap.containsKey("g")) defaultG = clampRgb(cMap.get("g"), defaultG);
                        if (cur.get("b") == null && cMap.containsKey("b")) defaultB = clampRgb(cMap.get("b"), defaultB);
                    }
                }

                int r = effectiveParams.containsKey("r") ? clampRgb(effectiveParams.get("r"), defaultR) : defaultR;
                int g = effectiveParams.containsKey("g") ? clampRgb(effectiveParams.get("g"), defaultG) : defaultG;
                int b = effectiveParams.containsKey("b") ? clampRgb(effectiveParams.get("b"), defaultB) : defaultB;

                effectiveParams.put("r", r);
                effectiveParams.put("g", g);
                effectiveParams.put("b", b);

                Map<String, Object> colorMap = new HashMap<>();
                colorMap.put("r", r);
                colorMap.put("g", g);
                colorMap.put("b", b);
                effectiveParams.put("color", colorMap);
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

                if ("SET_RGB".equalsIgnoreCase(normalizedAction)) {
                    payload.put("r", effectiveParams.get("r"));
                    payload.put("g", effectiveParams.get("g"));
                    payload.put("b", effectiveParams.get("b"));
                }

                // Normalization helpers for AC fields
                applyAcPayloadConversions(normalizedAction, payload, effectiveParams);

                payload.put("timestamp", System.currentTimeMillis());
            }
            
            String jsonPayload = objectMapper.writeValueAsString(payload);
            pendingCommands.put(commandId, future);
            
            // 1. Dispatch MQTT command immediately to broker (sub-second physical response)
            for (String targetTopic : targetTopics) {
                log.info("Publishing MQTT Command to {}: {}", targetTopic, jsonPayload);
                mqttGateway.sendToMqtt(targetTopic, 1, jsonPayload);
                if (diagnosticsService != null) {
                    diagnosticsService.recordPacket(targetTopic, "OUT", 1, jsonPayload);
                }
            }

            // 2. Persist device state without delaying MQTT physical dispatch
            if (device != null) {
                updateDeviceState(device, normalizedAction, effectiveParams);
            }
            
            long metricStartTime = System.currentTimeMillis();
            final String targetNodeCode = nodeCode;
            boolean isPowerCommand = "TURN_ON".equalsIgnoreCase(normalizedAction)
                    || "TURN_OFF".equalsIgnoreCase(normalizedAction)
                    || "POWER_ON".equalsIgnoreCase(normalizedAction)
                    || "POWER_OFF".equalsIgnoreCase(normalizedAction)
                    || "SET_POWER".equalsIgnoreCase(normalizedAction);

            long effectiveTimeout;
            if (source == StateChangeSource.VOICE) {
                effectiveTimeout = 250L;
            } else if (isColorOrDimmer) {
                effectiveTimeout = 75L;
            } else {
                effectiveTimeout = commandTimeoutMs;
            }

            return future.orTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
                    .handle((res, ex) -> {
                        activeDeviceCommands.remove(deviceId, future);
                        pendingCommands.remove(commandId, future);
                        long roundtrip = System.currentTimeMillis() - metricStartTime;

                        if (ex == null && res != null && res.isSuccess()) {
                            log.info("[METRICS] MQTT Roundtrip (Success) completed in {} ms for commandId {}", roundtrip, commandId);
                            res.setLatencyMs(roundtrip);
                            if (diagnosticsService != null && targetNodeCode != null && !"unknown".equalsIgnoreCase(targetNodeCode)) {
                                diagnosticsService.recordCommandSuccess(targetNodeCode, roundtrip);
                            }
                            return res;
                        } else {
                            boolean isTimeout = ex instanceof java.util.concurrent.TimeoutException
                                    || (ex != null && ex.getCause() instanceof java.util.concurrent.TimeoutException);

                            // Only fast continuous streaming (sliders) or Voice pipeline can return optimistic early completion
                            boolean isFastReturnEligible = isColorOrDimmer || (source == StateChangeSource.VOICE);
                            if (isFastReturnEligible && (isTimeout || (res != null && "SUPERSEDED".equals(res.getStatus())))) {
                                log.info("[METRICS] Fast-return for command {} commandId {} after {} ms (dispatched to MQTT broker, source: {})", action, commandId, roundtrip, source);
                                return CommandResult.builder()
                                        .commandId(commandId)
                                        .success(true)
                                        .status("SUCCESS")
                                        .message("Dispatched to MQTT broker")
                                        .acknowledgedState(effectiveParams)
                                        .latencyMs(roundtrip)
                                        .build();
                            }

                            log.warn("[METRICS] MQTT Roundtrip (Failure/Timeout) after {} ms for commandId {}. Ex: {}", roundtrip, commandId, ex != null ? ex.getMessage() : "Timeout/No-Ack");
                            if (diagnosticsService != null && targetNodeCode != null && !"unknown".equalsIgnoreCase(targetNodeCode)) {
                                diagnosticsService.recordCommandFailure(targetNodeCode);
                            }
                            String errCode = "TIMEOUT_NO_ACK";
                            String errMsg = "Device did not acknowledge within " + commandTimeoutMs + " ms";
                            if (ex != null && !isTimeout) {
                                errCode = "INTERNAL_ERROR";
                                errMsg = ex.getMessage() != null ? ex.getMessage() : "Unknown execution error";
                            }
                            return CommandResult.builder()
                                    .commandId(commandId)
                                    .success(false)
                                    .status("TIMEOUT")
                                    .errorCode(errCode)
                                    .message(errMsg)
                                    .latencyMs(roundtrip)
                                    .build();
                        }
                    });

        } catch (Exception e) {
            activeDeviceCommands.remove(deviceId, future);
            pendingCommands.remove(commandId, future);
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

    private int clampRgb(Object val, int defaultVal) {
        if (val == null) return defaultVal;
        try {
            int parsed;
            if (val instanceof Number n) {
                parsed = n.intValue();
            } else {
                parsed = (int) Math.round(Double.parseDouble(val.toString().trim()));
            }
            return Math.max(0, Math.min(255, parsed));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private String normalizeAction(Device device, String action, Map<String, Object> parameters) {
        if (action == null) return "UNKNOWN";
        String act = action.trim().toUpperCase();

        if ("SET_COLOR".equals(act) || "SET_RGB".equals(act)) {
            return "SET_RGB";
        }

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
                case "TURN_ON":
                    return "OPEN";
                case "GATE_CLOSE":
                case "CLOSE":
                case "TURN_OFF":
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
            case "ON":
            case "TRUE":
            case "BAT":
            case "MO":
                return "TURN_ON";
            case "POWER_ON":
                return "POWER_ON";
            case "TURN_OFF":
            case "OFF":
            case "FALSE":
            case "TAT":
            case "DONG":
                return "TURN_OFF";
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
                    int defaultRState = clampRgb(currentState.get("r"), 0);
                    int defaultGState = clampRgb(currentState.get("g"), 0);
                    int defaultBState = clampRgb(currentState.get("b"), 0);
                    if (currentState.get("color") instanceof Map<?, ?> existingColorMap) {
                        if (currentState.get("r") == null && existingColorMap.containsKey("r")) {
                            defaultRState = clampRgb(existingColorMap.get("r"), defaultRState);
                        }
                        if (currentState.get("g") == null && existingColorMap.containsKey("g")) {
                            defaultGState = clampRgb(existingColorMap.get("g"), defaultGState);
                        }
                        if (currentState.get("b") == null && existingColorMap.containsKey("b")) {
                            defaultBState = clampRgb(existingColorMap.get("b"), defaultBState);
                        }
                    }

                    int rVal = defaultRState;
                    int gVal = defaultGState;
                    int bVal = defaultBState;

                    if (parameters != null) {
                        if (parameters.containsKey("hex") && parameters.get("hex") instanceof String hexStr && hexStr.startsWith("#") && hexStr.length() == 7) {
                            try {
                                rVal = clampRgb(Integer.parseInt(hexStr.substring(1, 3), 16), defaultRState);
                                gVal = clampRgb(Integer.parseInt(hexStr.substring(3, 5), 16), defaultGState);
                                bVal = clampRgb(Integer.parseInt(hexStr.substring(5, 7), 16), defaultBState);
                            } catch (Exception ignored) {}
                        }

                        Object rObj = parameters.get("r");
                        Object gObj = parameters.get("g");
                        Object bObj = parameters.get("b");

                        if (parameters.get("color") instanceof Map<?, ?> colorMapParam) {
                            if (rObj == null) rObj = colorMapParam.get("r");
                            if (gObj == null) gObj = colorMapParam.get("g");
                            if (bObj == null) bObj = colorMapParam.get("b");
                        } else if (parameters.get("color") instanceof String cHex && cHex.startsWith("#") && cHex.length() == 7) {
                            try {
                                rVal = clampRgb(Integer.parseInt(cHex.substring(1, 3), 16), defaultRState);
                                gVal = clampRgb(Integer.parseInt(cHex.substring(3, 5), 16), defaultGState);
                                bVal = clampRgb(Integer.parseInt(cHex.substring(5, 7), 16), defaultBState);
                            } catch (Exception ignored) {}
                        }

                        if (rObj != null) rVal = clampRgb(rObj, defaultRState);
                        if (gObj != null) gVal = clampRgb(gObj, defaultGState);
                        if (bObj != null) bVal = clampRgb(bObj, defaultBState);
                    }

                    Map<String, Object> colorMap = new HashMap<>();
                    colorMap.put("r", rVal);
                    colorMap.put("g", gVal);
                    colorMap.put("b", bVal);
                    currentState.put("color", colorMap);
                    currentState.put("r", rVal);
                    currentState.put("g", gVal);
                    currentState.put("b", bVal);

                    if (parameters != null && (parameters.containsKey("brightness") || parameters.containsKey("level"))) {
                        Object br = parameters.containsKey("brightness") ? parameters.get("brightness") : parameters.get("level");
                        if (br != null) {
                            try {
                                currentState.put("brightness", Math.max(0, Math.min(100, Integer.parseInt(br.toString()))));
                            } catch (Exception ignored) {}
                        }
                    }
                    break;
                case "SET_BRIGHTNESS":
                    if (parameters != null) {
                        Object br = parameters.containsKey("brightness") ? parameters.get("brightness") : parameters.get("level");
                        if (br != null) {
                            try {
                                int brInt = Math.max(0, Math.min(100, Integer.parseInt(br.toString())));
                                currentState.put("brightness", brInt);
                                if (brInt > 0) {
                                    currentState.put("power", "ON");
                                }
                            } catch (Exception ignored) {
                                currentState.put("brightness", br);
                            }
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

            UUID homeId = getHomeId(device);
            if (homeId != null) {
                try {
                    TwinDeviceSnapshotResponse snapshot = twinSnapshotMapper != null
                            ? twinSnapshotMapper.device(device)
                            : new TwinDeviceSnapshotResponse(
                                    device.getId(),
                                    device.getRoom() != null ? device.getRoom().getId() : null,
                                    device.getName(),
                                    device.getDeviceType(),
                                    device.getIcon(),
                                    device.getStatus(),
                                    objectMapper.valueToTree(currentState),
                                    device.getLastSeen(),
                                    TwinHealthStatus.ACTIVE
                            );

                    // 1. Gửi sự kiện cho Spring Transactional Listeners
                    if (applicationEventPublisher != null) {
                        applicationEventPublisher.publishEvent(new DeviceStateChangedEvent(
                                homeId,
                                snapshot
                        ));
                        log.info("Published DeviceStateChangedEvent for device {} in home {}", device.getId(), homeId);
                    }

                    // 2. Server Optimistic Broadcast: Bắn WebSocket STOMP tức thì (<1ms)
                    // Giúp toàn bộ màn hình của các người dùng khác đồng bộ trạng thái ngay lập tức
                    if (realtimeEventPublisher != null) {
                        realtimeEventPublisher.publish(RealtimeEvent.create(
                                RealtimeEventType.DEVICE_STATE_CHANGED,
                                homeId,
                                device.getId(),
                                snapshot
                        ));
                        log.info("Server Optimistic Broadcast sent via realtimeEventPublisher for device {} in home {}", device.getId(), homeId);
                    }
                } catch (Exception e) {
                    log.error("Failed to publish realtime state for device {}", device.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to update currentState for device {}", device.getId(), e);
        }
    }

    private UUID getHomeId(Device device) {
        if (device == null) return null;
        try {
            if (device.getRoom() != null && device.getRoom().getHome() != null) {
                return device.getRoom().getHome().getId();
            }
            if (device.getNode() != null && device.getNode().getHome() != null) {
                return device.getNode().getHome().getId();
            }
            if (device.getHome() != null) {
                return device.getHome().getId();
            }
        } catch (Exception ex) {
            log.warn("Could not determine homeId for device {}: {}", device.getId(), ex.getMessage());
        }
        return null;
    }
}
