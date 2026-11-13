package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hesta.backend.dto.response.SceneExecutionResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.entity.SceneExecution;
import com.hesta.backend.enums.ExecutionStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.SceneExecutionRepository;
import com.hesta.backend.repository.SceneRepository;
import com.hesta.backend.service.MemberAccessContext;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.service.BehaviorEventRecorder;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.ManualOverrideService;
import com.hesta.backend.service.SceneExecutionService;
import com.hesta.backend.service.execution.CommandBatchRunner;
import com.hesta.backend.service.execution.CommandBatchRunner.PlannedCommand;
import com.hesta.backend.service.execution.DeviceActionValues;
import com.hesta.backend.service.execution.DeviceActionValues.DeviceCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runs a scene: turns each scene action into device commands, sends them, stores the result. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SceneExecutionServiceImpl implements SceneExecutionService {
    private final HomeAuthorizationService homeAuthorizationService;
    private final SceneRepository sceneRepository;
    private final SceneExecutionRepository executionRepository;
    private final BehaviorEventRecorder behaviorEventRecorder;
    private final ManualOverrideService manualOverrideService;
    private final CommandBatchRunner commandBatchRunner;

    @Override
    @Transactional
    public SceneExecutionResponse execute(UUID userId, UUID homeId, UUID sceneId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext ctx = homeAuthorizationService.loadAccessContext(homeId, userId);
        if (ctx != null && !ctx.isOwner() && ctx.scope() == AccessScope.CUSTOM) {
            if (!homeAuthorizationService.canRunScene(userId, sceneId)) {
                throw new AppException(ErrorCode.UNAUTHORIZED);
            }
        }
        return runAndRecord(find(homeId, sceneId), userId, "MANUAL", StateChangeSource.SCENE);
    }

    @Override
    @Transactional
    public SceneExecutionResponse executeScheduled(UUID homeId, UUID sceneId) {
        return runAndRecord(find(homeId, sceneId), null, "SCHEDULE", StateChangeSource.SCHEDULE);
    }

    @Override
    @Transactional
    public SceneExecutionResponse executeFromAutomation(UUID homeId, UUID sceneId) {
        return runAndRecord(find(homeId, sceneId), null, "AUTOMATION", StateChangeSource.AUTOMATION);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SceneExecutionResponse> history(UUID userId, UUID homeId, UUID sceneId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext ctx = homeAuthorizationService.loadAccessContext(homeId, userId);
        if (ctx != null && !ctx.isOwner() && ctx.scope() == AccessScope.CUSTOM) {
            if (!homeAuthorizationService.canRunScene(userId, sceneId)) {
                throw new AppException(ErrorCode.SCENE_NOT_FOUND);
            }
        }
        find(homeId, sceneId);
        return executionRepository.findTop100BySceneIdOrderByStartedAtDesc(sceneId).stream()
                .map(this::toResponse).toList();
    }

    private SceneExecutionResponse runAndRecord(Scene scene, UUID userId, String trigger, StateChangeSource source) {
        SceneExecutionResponse result = run(scene, trigger, source);
        try {
            behaviorEventRecorder.recordExecution(scene.getHome(), userId, "SCENE_EXECUTION", scene.getId(), result.getStatus());
        } catch (RuntimeException recordingFailure) {
            log.warn("Could not record scene execution {}", result.getId(), recordingFailure);
        }
        return result;
    }

    private SceneExecutionResponse run(Scene scene, String trigger, StateChangeSource source) {
        if (!scene.isEnabled()) throw new AppException(ErrorCode.SCENE_DISABLED);
        OffsetDateTime startedAt = OffsetDateTime.now();
        ArrayNode details = JsonNodeFactory.instance.arrayNode();
        List<PlannedCommand> commands = new ArrayList<>();

        for (SceneAction action : scene.getActions().stream().sorted(Comparator.comparingInt(SceneAction::getOrder)).toList()) {
            Device device = action.getTargetDevice();
            // A person who just changed a device by hand wins over schedules/automations, but not over pressing the scene.
            if (!"MANUAL".equals(trigger) && manualOverrideService.isActive(device.getId())) {
                details.add(CommandBatchRunner.skip(CommandBatchRunner.detail(device.getId(), action.getAction()), "SKIPPED_OVERRIDE"));
                continue;
            }
            for (DeviceCommand command : toCommands(action)) {
                ObjectNode detail = CommandBatchRunner.detail(device.getId(), command.action());
                details.add(detail);
                commands.add(new PlannedCommand(device, command.action(), command.parameters(), detail));
            }
        }

        int succeeded = commandBatchRunner.run(commands, source);
        ExecutionStatus status = CommandBatchRunner.status(details.size(), commands.size(), succeeded);
        SceneExecution execution = SceneExecution.builder().scene(scene).triggerSource(trigger)
                .status(status).startedAt(startedAt).completedAt(OffsetDateTime.now())
                .resultDetail(details).build();
        return toResponse(executionRepository.save(execution));
    }

    private List<DeviceCommand> toCommands(SceneAction action) {
        Map<String, Object> parameters = DeviceActionValues.toParameters(action.getAction(), action.getValue());
        return "SET_STATE".equalsIgnoreCase(action.getAction())
                ? DeviceActionValues.expandSetState(parameters)
                : List.of(new DeviceCommand(action.getAction(), parameters));
    }

    private Scene find(UUID homeId, UUID sceneId) {
        return sceneRepository.findByIdAndHomeId(sceneId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.SCENE_NOT_FOUND));
    }

    private SceneExecutionResponse toResponse(SceneExecution execution) {
        return SceneExecutionResponse.builder().id(execution.getId())
                .sceneId(execution.getScene().getId()).triggerSource(execution.getTriggerSource())
                .status(execution.getStatus().name()).startedAt(execution.getStartedAt())
                .completedAt(execution.getCompletedAt()).resultDetail(execution.getResultDetail()).build();
    }
}
