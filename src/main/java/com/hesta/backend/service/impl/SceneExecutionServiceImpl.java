package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.response.SceneExecutionResponse;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.entity.SceneExecution;
import com.hesta.backend.enums.ExecutionStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.SceneExecutionRepository;
import com.hesta.backend.repository.SceneRepository;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.SceneExecutionService;
import com.hesta.backend.service.BehaviorEventRecorder;
import com.hesta.backend.service.ManualOverrideService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SceneExecutionServiceImpl implements SceneExecutionService {
    private final HomeAuthorizationService homeAuthorizationService;
    private final SceneRepository sceneRepository;
    private final SceneExecutionRepository executionRepository;
    private final DeviceCommandService deviceCommandService;
    private final ObjectMapper objectMapper;
    private final BehaviorEventRecorder behaviorEventRecorder;
    private final ManualOverrideService manualOverrideService;

    @Override
    @Transactional
    public SceneExecutionResponse execute(UUID userId, UUID homeId, UUID sceneId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        Scene scene = find(homeId, sceneId);
        SceneExecutionResponse result = run(scene, "MANUAL", StateChangeSource.SCENE);
        recordExecution(scene, userId, result);
        return result;
    }

    @Override
    @Transactional
    public SceneExecutionResponse executeScheduled(UUID homeId, UUID sceneId) {
        Scene scene = find(homeId, sceneId);
        SceneExecutionResponse result = run(scene, "SCHEDULE", StateChangeSource.SCHEDULE);
        recordExecution(scene, null, result);
        return result;
    }

    @Override
    @Transactional
    public SceneExecutionResponse executeFromAutomation(UUID homeId, UUID sceneId) {
        Scene scene = find(homeId, sceneId);
        SceneExecutionResponse result = run(scene, "AUTOMATION", StateChangeSource.AUTOMATION);
        recordExecution(scene, null, result);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SceneExecutionResponse> history(UUID userId, UUID homeId, UUID sceneId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        find(homeId, sceneId);
        return executionRepository.findTop100BySceneIdOrderByStartedAtDesc(sceneId).stream()
                .map(this::toResponse).toList();
    }

    private Scene find(UUID homeId, UUID sceneId) {
        return sceneRepository.findByIdAndHomeId(sceneId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.SCENE_NOT_FOUND));
    }

    private void recordExecution(Scene scene, UUID userId, SceneExecutionResponse result) {
        try {
            behaviorEventRecorder.recordExecution(scene.getHome(), userId, "SCENE_EXECUTION", scene.getId(), result.getStatus());
        } catch (RuntimeException recordingFailure) {
            log.warn("Could not record scene execution {}", result.getId(), recordingFailure);
        }
    }

    private record DiscreteCommand(String action, Map<String, Object> parameters) {}

    private SceneExecutionResponse run(Scene scene, String trigger, StateChangeSource source) {
        if (!scene.isEnabled()) throw new AppException(ErrorCode.SCENE_DISABLED);
        OffsetDateTime started = OffsetDateTime.now();
        ArrayNode details = objectMapper.createArrayNode();
        int successes = 0;
        int attempted = 0;
        List<SceneAction> actions = scene.getActions().stream()
                .sorted(Comparator.comparingInt(SceneAction::getOrder)).toList();
        for (SceneAction action : actions) {
            if (!"MANUAL".equals(trigger) && manualOverrideService.isActive(action.getTargetDevice().getId())) {
                ObjectNode detail = objectMapper.createObjectNode();
                detail.put("deviceId", action.getTargetDevice().getId().toString());
                detail.put("action", action.getAction());
                detail.put("success", false);
                detail.put("status", "SKIPPED_OVERRIDE");
                details.add(detail);
                continue;
            }

            List<DiscreteCommand> commandsToRun;
            if ("SET_STATE".equalsIgnoreCase(action.getAction())) {
                commandsToRun = decomposeSetState(parameters(action));
            } else {
                commandsToRun = List.of(new DiscreteCommand(action.getAction(), parameters(action)));
            }

            for (DiscreteCommand cmd : commandsToRun) {
                attempted++;
                ObjectNode detail = objectMapper.createObjectNode();
                detail.put("deviceId", action.getTargetDevice().getId().toString());
                detail.put("action", cmd.action());
                try {
                    CommandResult result = deviceCommandService.sendCommand(action.getTargetDevice().getId(),
                            cmd.action(), cmd.parameters(), source).join();
                    detail.put("success", result.isSuccess());
                    detail.put("status", result.getStatus());
                    if (result.getCommandId() != null) detail.put("commandId", result.getCommandId());
                    if (result.getErrorCode() != null) detail.put("errorCode", result.getErrorCode());
                    if (result.getMessage() != null) detail.put("message", result.getMessage());
                    if (result.isSuccess()) {
                        successes++;
                        try {
                            behaviorEventRecorder.recordCommand(action.getTargetDevice(), cmd.action(), source, result);
                        } catch (RuntimeException recordingFailure) {
                            log.warn("Could not record scene command for device {}", action.getTargetDevice().getId(), recordingFailure);
                        }
                    }
                } catch (RuntimeException exception) {
                    detail.put("success", false);
                    detail.put("status", "FAILED");
                    detail.put("message", "Device command failed");
                }
                details.add(detail);
            }
        }
        ExecutionStatus status = attempted == 0 ? ExecutionStatus.SKIPPED
                : successes == attempted ? ExecutionStatus.SUCCESS
                : successes == 0 ? ExecutionStatus.FAILED : ExecutionStatus.PARTIAL;
        SceneExecution execution = SceneExecution.builder().scene(scene).triggerSource(trigger)
                .status(status).startedAt(started).completedAt(OffsetDateTime.now())
                .resultDetail(details).build();
        return toResponse(executionRepository.save(execution));
    }

    private List<DiscreteCommand> decomposeSetState(Map<String, Object> params) {
        List<DiscreteCommand> list = new ArrayList<>();
        if (params == null || params.isEmpty()) {
            list.add(new DiscreteCommand("SET_STATE", Map.of()));
            return list;
        }

        // 1. Power command
        if (params.containsKey("power")) {
            Object p = params.get("power");
            boolean isPowerOff = Boolean.FALSE.equals(p) || "OFF".equalsIgnoreCase(String.valueOf(p)) || "false".equalsIgnoreCase(String.valueOf(p)) || "0".equals(String.valueOf(p));
            boolean isPowerOn = Boolean.TRUE.equals(p) || "ON".equalsIgnoreCase(String.valueOf(p)) || "true".equalsIgnoreCase(String.valueOf(p)) || "1".equals(String.valueOf(p));
            if (isPowerOff) {
                list.add(new DiscreteCommand("TURN_OFF", Map.of()));
                return list; // Turning off device terminates other setting commands
            } else if (isPowerOn) {
                list.add(new DiscreteCommand("TURN_ON", Map.of()));
            }
        }

        // 2. Brightness command
        if (params.containsKey("brightness") || params.containsKey("level")) {
            Object br = params.containsKey("brightness") ? params.get("brightness") : params.get("level");
            Map<String, Object> brParams = new HashMap<>();
            brParams.put("brightness", br);
            brParams.put("level", br);
            list.add(new DiscreteCommand("SET_BRIGHTNESS", brParams));
        }

        // 3. Color / RGB command
        if (params.containsKey("r") || params.containsKey("color") || params.containsKey("hex")) {
            Map<String, Object> colorParams = new HashMap<>();
            colorParams.put("power", "ON");
            if (params.containsKey("brightness")) colorParams.put("brightness", params.get("brightness"));
            else if (params.containsKey("level")) colorParams.put("brightness", params.get("level"));
            else colorParams.put("brightness", 100);

            if (params.containsKey("r") && params.containsKey("g") && params.containsKey("b")) {
                try {
                    int r = Integer.parseInt(params.get("r").toString());
                    int g = Integer.parseInt(params.get("g").toString());
                    int b = Integer.parseInt(params.get("b").toString());
                    colorParams.put("r", r);
                    colorParams.put("g", g);
                    colorParams.put("b", b);
                    colorParams.put("hex", String.format("#%02x%02x%02x", r, g, b));
                    colorParams.put("color", String.format("#%02x%02x%02x", r, g, b));
                } catch (Exception ignored) {}
            } else if (params.containsKey("hex") || params.containsKey("color")) {
                String hex = params.containsKey("hex") ? String.valueOf(params.get("hex")) : String.valueOf(params.get("color"));
                parseHexIntoMap(hex, colorParams);
            }
            list.add(new DiscreteCommand("SET_COLOR", colorParams));
        }

        // 4. AC Temperature
        if (params.containsKey("temperature") || params.containsKey("temp")) {
            Object temp = params.containsKey("temperature") ? params.get("temperature") : params.get("temp");
            list.add(new DiscreteCommand("SET_TEMPERATURE", Map.of("temperature", temp, "temp", temp)));
        }

        // 5. AC Mode
        if (params.containsKey("mode")) {
            list.add(new DiscreteCommand("SET_MODE", Map.of("mode", params.get("mode").toString().toUpperCase())));
        }

        // 6. Fan / Speed
        if (params.containsKey("fan") || params.containsKey("speed")) {
            Object fan = params.containsKey("fan") ? params.get("fan") : params.get("speed");
            list.add(new DiscreteCommand("SET_SPEED", Map.of("speed", fan, "fan", fan)));
        }

        // 7. Gate
        if (params.containsKey("gateAction") || params.containsKey("state")) {
            Object ga = params.containsKey("gateAction") ? params.get("gateAction") : params.get("state");
            String actStr = String.valueOf(ga).toUpperCase();
            if (List.of("OPEN", "CLOSE", "STOP").contains(actStr)) {
                list.add(new DiscreteCommand(actStr, Map.of()));
            }
        }

        if (list.isEmpty()) {
            list.add(new DiscreteCommand("SET_STATE", params));
        }

        return list;
    }

    private Map<String, Object> parameters(SceneAction action) {
        JsonNode value = action.getValue();
        if (value == null || value.isNull()) return Map.of();
        String act = action.getAction() != null ? action.getAction().toUpperCase() : "";
        return switch (act) {
            case "SET_BRIGHTNESS" -> {
                if (value.isObject()) {
                    JsonNode brNode = value.has("brightness") ? value.get("brightness") : value.get("level");
                    Number br = brNode != null && brNode.isNumber() ? brNode.numberValue() : 50;
                    Map<String, Object> params = new HashMap<>();
                    params.put("brightness", br);
                    params.put("level", br);
                    yield params;
                }
                Map<String, Object> params = new HashMap<>();
                params.put("brightness", value.numberValue());
                params.put("level", value.numberValue());
                yield params;
            }
            case "SET_SPEED" -> Map.of("speed", value.numberValue());
            case "SET_TEMPERATURE" -> {
                Map<String, Object> params = new HashMap<>();
                params.put("temperature", value.numberValue());
                params.put("temp", value.numberValue());
                yield params;
            }
            case "SET_COLOR", "SET_RGB" -> extractColorFromNode(value);
            case "SET_MODE" -> Map.of("mode", value.asText().toUpperCase());
            case "SET_FAN" -> {
                Map<String, Object> params = new HashMap<>();
                params.put("fan", value.asText().toUpperCase());
                params.put("speed", value.asText().toUpperCase());
                yield params;
            }
            case "SET_POWER" -> Map.of("power", value.isBoolean() ? value.booleanValue() : "ON".equalsIgnoreCase(value.asText()));
            case "SET_STATE" -> {
                if (value.isObject()) {
                    yield objectMapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                }
                yield Map.of();
            }
            default -> {
                if (value.isObject()) {
                    yield objectMapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                }
                yield Map.of();
            }
        };
    }

    private Map<String, Object> extractColorFromNode(JsonNode value) {
        Map<String, Object> result = new HashMap<>();
        result.put("power", "ON");
        result.put("brightness", 100);

        if (value.isObject()) {
            if (value.has("brightness") && value.get("brightness").isNumber()) {
                result.put("brightness", value.get("brightness").numberValue());
            } else if (value.has("level") && value.get("level").isNumber()) {
                result.put("brightness", value.get("level").numberValue());
            }

            if (value.has("r") && value.has("g") && value.has("b")) {
                int r = value.get("r").asInt();
                int g = value.get("g").asInt();
                int b = value.get("b").asInt();
                result.put("r", r);
                result.put("g", g);
                result.put("b", b);
                result.put("hex", String.format("#%02x%02x%02x", r, g, b));
                result.put("color", String.format("#%02x%02x%02x", r, g, b));
            } else if (value.has("hex") || value.has("color")) {
                String hex = value.has("hex") ? value.get("hex").asText() : value.get("color").asText();
                parseHexIntoMap(hex, result);
            }
        } else if (value.isTextual()) {
            String text = value.asText().trim();
            if (text.startsWith("{")) {
                try {
                    JsonNode parsed = objectMapper.readTree(text);
                    return extractColorFromNode(parsed);
                } catch (Exception ignored) {}
            }
            parseHexIntoMap(text, result);
        }
        return result;
    }

    private void parseHexIntoMap(String hex, Map<String, Object> result) {
        if (hex != null && hex.startsWith("#") && hex.length() == 7) {
            try {
                int r = Integer.parseInt(hex.substring(1, 3), 16);
                int g = Integer.parseInt(hex.substring(3, 5), 16);
                int b = Integer.parseInt(hex.substring(5, 7), 16);
                result.put("r", r);
                result.put("g", g);
                result.put("b", b);
                result.put("hex", hex);
                result.put("color", hex);
            } catch (Exception ignored) {}
        }
    }

    private SceneExecutionResponse toResponse(SceneExecution execution) {
        return SceneExecutionResponse.builder().id(execution.getId())
                .sceneId(execution.getScene().getId()).triggerSource(execution.getTriggerSource())
                .status(execution.getStatus().name()).startedAt(execution.getStartedAt())
                .completedAt(execution.getCompletedAt()).resultDetail(execution.getResultDetail()).build();
    }
}
