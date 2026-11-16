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
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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
    // Field-injected so the existing constructor (and its tests) stay unchanged; only the async run needs them.
    // Own pool, not the automation pool: every /state and sensor message queues a rule check there,
    // and a scene the user just tapped must not wait behind that backlog.
    private Executor sceneRunner = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "scene-run");
        thread.setDaemon(true);
        return thread;
    });
    @Autowired(required = false) private PlatformTransactionManager transactionManager;
    @Autowired(required = false) private RealtimeEventPublisher realtimeEventPublisher;

    @Override
    @Transactional
    public SceneExecutionResponse execute(UUID userId, UUID homeId, UUID sceneId) {
        requireRunAccess(userId, homeId, sceneId);
        return runAndRecord(find(homeId, sceneId), userId, "MANUAL", StateChangeSource.SCENE);
    }

    @Override
    @Transactional
    public SceneExecutionResponse executeAsync(UUID userId, UUID homeId, UUID sceneId) {
        requireRunAccess(userId, homeId, sceneId);
        Scene scene = find(homeId, sceneId);
        if (!scene.isEnabled()) throw new AppException(ErrorCode.SCENE_DISABLED);
        OffsetDateTime startedAt = OffsetDateTime.now();
        // completed_at is NOT NULL in the schema: hold startedAt until the run finishes; status PENDING marks it unfinished.
        SceneExecution pending = executionRepository.save(SceneExecution.builder().scene(scene).triggerSource("MANUAL")
                .status(ExecutionStatus.PENDING).startedAt(startedAt).completedAt(startedAt)
                .resultDetail(JsonNodeFactory.instance.arrayNode()).build());
        UUID executionId = pending.getId();
        // Start only after the PENDING row is committed, so the background transaction can read it.
        Runnable start = () -> sceneRunner.execute(() -> finishInBackground(executionId, homeId, userId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    start.run();
                }
            });
        } else {
            start.run();
        }
        return toResponse(pending);
    }

    private void requireRunAccess(UUID userId, UUID homeId, UUID sceneId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext ctx = homeAuthorizationService.loadAccessContext(homeId, userId);
        if (ctx != null && !ctx.isOwner() && ctx.scope() == AccessScope.CUSTOM) {
            if (!homeAuthorizationService.canRunScene(userId, sceneId)) {
                throw new AppException(ErrorCode.UNAUTHORIZED);
            }
        }
    }

    /** Runs a PENDING execution, pushing its rows after every command and the final result at the end. */
    private void finishInBackground(UUID executionId, UUID homeId, UUID userId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        SceneExecutionResponse result;
        try {
            // Short transactions on both sides: no DB connection is held while the devices answer.
            SceneExecution[] loaded = new SceneExecution[1];
            Plan plan = tx.execute(status -> {
                loaded[0] = executionRepository.findById(executionId).orElseThrow();
                return plan(loaded[0].getScene(), loaded[0].getTriggerSource());
            });
            SceneExecution execution = loaded[0];
            execution.setResultDetail(plan.details());
            int succeeded = commandBatchRunner.run(plan.commands(), StateChangeSource.SCENE,
                    () -> publishUpdate(homeId, toResponse(execution)));
            complete(execution, plan, succeeded);
            result = tx.execute(status -> {
                SceneExecution saved = executionRepository.save(execution);
                recordExecution(saved.getScene(), userId, toResponse(saved));
                return toResponse(saved);
            });
        } catch (RuntimeException failure) {
            // Never leave a run PENDING forever: the client is waiting for a final status.
            log.warn("Scene execution {} failed in background", executionId, failure);
            result = tx.execute(status -> executionRepository.findById(executionId).map(execution -> {
                execution.setStatus(ExecutionStatus.FAILED);
                execution.setCompletedAt(OffsetDateTime.now());
                return toResponse(executionRepository.save(execution));
            }).orElse(null));
        }
        if (result != null) publishUpdate(homeId, result);
    }

    private void publishUpdate(UUID homeId, SceneExecutionResponse execution) {
        if (realtimeEventPublisher == null) return;
        try {
            realtimeEventPublisher.publish(RealtimeEvent.create(RealtimeEventType.SCENE_EXECUTION_UPDATED, homeId, execution));
        } catch (RuntimeException publishFailure) {
            log.warn("Could not publish scene execution {}", execution.getId(), publishFailure);
        }
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
        recordExecution(scene, userId, result);
        return result;
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
        SceneExecution execution = SceneExecution.builder().scene(scene).triggerSource(trigger)
                .startedAt(OffsetDateTime.now()).build();
        runCommands(execution, source, null);
        return toResponse(executionRepository.save(execution));
    }

    /** Sends the scene's commands and writes resultDetail, status and completedAt into {@code execution}. */
    private void runCommands(SceneExecution execution, StateChangeSource source, Runnable afterEachRound) {
        Plan plan = plan(execution.getScene(), execution.getTriggerSource());
        execution.setResultDetail(plan.details());
        complete(execution, plan, commandBatchRunner.run(plan.commands(), source, afterEachRound));
    }

    /** The scene's device commands, each with its (still empty) result row. Needs an open session for lazy devices. */
    private record Plan(ArrayNode details, List<PlannedCommand> commands) {}

    private Plan plan(Scene scene, String trigger) {
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

        return new Plan(details, commands);
    }

    private static void complete(SceneExecution execution, Plan plan, int succeeded) {
        execution.setStatus(CommandBatchRunner.status(plan.details().size(), plan.commands().size(), succeeded));
        execution.setCompletedAt(OffsetDateTime.now());
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
