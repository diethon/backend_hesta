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
import com.hesta.backend.enums.DeviceAction;
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
import java.util.Comparator;
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

    private SceneExecutionResponse run(Scene scene, String trigger, StateChangeSource source) {
        if (!scene.isEnabled()) throw new AppException(ErrorCode.SCENE_DISABLED);
        OffsetDateTime started = OffsetDateTime.now();
        ArrayNode details = objectMapper.createArrayNode();
        int successes = 0;
        int attempted = 0;
        List<SceneAction> actions = scene.getActions().stream()
                .sorted(Comparator.comparingInt(SceneAction::getOrder)).toList();
        for (SceneAction action : actions) {
            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("deviceId", action.getTargetDevice().getId().toString());
            detail.put("action", action.getAction());
            if (!"MANUAL".equals(trigger) && manualOverrideService.isActive(action.getTargetDevice().getId())) {
                detail.put("success", false);
                detail.put("status", "SKIPPED_OVERRIDE");
                details.add(detail);
                continue;
            }
            attempted++;
            try {
                CommandResult result = deviceCommandService.sendCommand(action.getTargetDevice().getId(),
                        DeviceAction.valueOf(action.getAction()), parameters(action), source).join();
                detail.put("success", result.isSuccess());
                detail.put("status", result.getStatus());
                if (result.getCommandId() != null) detail.put("commandId", result.getCommandId());
                if (result.getErrorCode() != null) detail.put("errorCode", result.getErrorCode());
                if (result.getMessage() != null) detail.put("message", result.getMessage());
                if (result.isSuccess()) {
                    successes++;
                    try {
                        behaviorEventRecorder.recordCommand(action.getTargetDevice(), DeviceAction.valueOf(action.getAction()), source, result);
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
        ExecutionStatus status = attempted == 0 ? ExecutionStatus.SKIPPED
                : successes == actions.size() ? ExecutionStatus.SUCCESS
                : successes == 0 ? ExecutionStatus.FAILED : ExecutionStatus.PARTIAL;
        SceneExecution execution = SceneExecution.builder().scene(scene).triggerSource(trigger)
                .status(status).startedAt(started).completedAt(OffsetDateTime.now())
                .resultDetail(details).build();
        return toResponse(executionRepository.save(execution));
    }

    private Map<String, Object> parameters(SceneAction action) {
        JsonNode value = action.getValue();
        if (value == null || value.isNull()) return Map.of();
        return switch (DeviceAction.valueOf(action.getAction())) {
            case SET_BRIGHTNESS -> Map.of("level", value.numberValue());
            case SET_SPEED -> Map.of("speed", value.numberValue());
            case SET_TEMPERATURE -> Map.of("temperature", value.numberValue());
            case SET_STATE -> objectMapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            default -> Map.of();
        };
    }

    private SceneExecutionResponse toResponse(SceneExecution execution) {
        return SceneExecutionResponse.builder().id(execution.getId())
                .sceneId(execution.getScene().getId()).triggerSource(execution.getTriggerSource())
                .status(execution.getStatus().name()).startedAt(execution.getStartedAt())
                .completedAt(execution.getCompletedAt()).resultDetail(execution.getResultDetail()).build();
    }
}
