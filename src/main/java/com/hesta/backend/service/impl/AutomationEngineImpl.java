package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.dto.response.AutomationEngineResponse;
import com.hesta.backend.dto.response.AutomationExecutionResponse;
import com.hesta.backend.dto.response.SceneExecutionResponse;
import com.hesta.backend.entity.AutomationExecution;
import com.hesta.backend.entity.AutomationRule;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.RuleAction;
import com.hesta.backend.enums.ExecutionStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.enums.TriggerType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.AutomationExecutionRepository;
import com.hesta.backend.repository.AutomationRuleRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.AutomationEngine;
import com.hesta.backend.service.BehaviorEventRecorder;
import com.hesta.backend.service.ManualOverrideService;
import com.hesta.backend.service.SceneExecutionService;
import com.hesta.backend.service.automation.ActionConflictDetector;
import com.hesta.backend.service.automation.RuleMatcher;
import com.hesta.backend.service.automation.RuleTiming;
import com.hesta.backend.service.automation.RuleTriggerGate;
import com.hesta.backend.service.execution.CommandBatchRunner;
import com.hesta.backend.service.execution.CommandBatchRunner.PlannedCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs automation rules. For each incoming event:
 * <ol>
 *   <li>{@link RuleMatcher} checks the rule's conditions,</li>
 *   <li>{@link RuleTriggerGate} drops repeats (edge trigger + cooldown),</li>
 *   <li>{@link ActionConflictDetector} skips rules that would undo an earlier rule,</li>
 *   <li>the rule's actions run (scenes via the scene service, devices via {@link CommandBatchRunner}).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationEngineImpl implements AutomationEngine {
    private static final Set<String> EMERGENCY_WORDS = Set.of("FIRE", "SMOKE", "GAS", "LEAK", "EMERGENCY");
    private static final Comparator<AutomationRule> OLDEST_FIRST = Comparator
            .comparing(AutomationRule::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(AutomationRule::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final AutomationRuleRepository ruleRepository;
    private final AutomationExecutionRepository executionRepository;
    private final DeviceRepository deviceRepository;
    private final BehaviorEventRecorder behaviorEventRecorder;
    private final ManualOverrideService manualOverrideService;
    private final SceneExecutionService sceneExecutionService;
    private final RuleMatcher ruleMatcher;
    private final RuleTriggerGate triggerGate;
    private final CommandBatchRunner commandBatchRunner;
    // Field-injected so the constructor (and its tests) stay unchanged.
    @Autowired(required = false) private PlatformTransactionManager transactionManager;

    /** Waits between stages, auto-offs and hold timers: virtual threads, so a sleeping wait costs no pool thread. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
    // Package-visible and non-final so tests can run them inline without real waiting.
    ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    Sleeper sleeper = Thread::sleep;
    // shortcut: pending holds live in memory; a restart drops them (the next matching report starts a new one).
    private final Map<UUID, Future<?>> pendingHolds = new ConcurrentHashMap<>();

    // process/executeScheduled are deliberately not @Transactional: matching and planning run in one short
    // transaction, the device commands (each waits for an ACK) run outside any, and each result is saved in its own.
    // A transaction held across the ACK waits pins a pooled DB connection per running rule and starves other requests.
    @Override
    public AutomationEngineResponse process(UUID homeId, AutomationEventRequest event) {
        int[] evaluated = new int[1];
        List<RulePlan> plans = inTx(() -> {
            Device source = requireSourceDeviceInHome(homeId, event);
            if (source != null && !event.isTest() && !event.isCommandEcho()) {
                try {
                    behaviorEventRecorder.recordSensor(source, event);
                } catch (Exception ex) {
                    log.error("Failed to record sensor behavior event for device {}: {}", source.getId(), ex.getMessage(), ex);
                }
            }

            List<AutomationRule> rules = ruleRepository.findAllByHomeIdAndEnabledTrueAndTriggerTypeIn(
                    homeId, List.of(TriggerType.SENSOR, TriggerType.EVENT));
            if (!rules.isEmpty()) ruleRepository.fetchActionsByIdIn(rules.stream().map(AutomationRule::getId).toList());
            evaluated[0] = rules.size();

            boolean emergency = isEmergency(event, source);
            ActionConflictDetector conflicts = new ActionConflictDetector();
            List<RulePlan> planned = new ArrayList<>();
            for (AutomationRule rule : rules.stream().sorted(OLDEST_FIRST).toList()) {
                boolean matched = ruleMatcher.matches(rule, event);
                // The conditions turned false while a hold was counting: it starts over at the next match.
                if (!matched && ruleMatcher.isRelevant(rule, event)) cancelHold(rule.getId());
                // Claimed before running, so a conflict skip also waits for the next edge (no SKIPPED row on every report).
                if (!triggerGate.tryClaim(rule.getId(), event, matched, emergency)) continue;
                if (rule.getHoldSeconds() > 0 && !emergency && !event.isTest()) {
                    startHold(homeId, rule, event);
                    continue;
                }
                if (conflicts.conflictsWithReserved(rule)) {
                    planned.add(RulePlan.done(saveSkippedConflict(rule, event)));
                    continue;
                }
                RulePlan plan = plan(rule, event, emergency);
                planned.add(plan);
                // shortcut: reserves when something will be attempted; a scene whose every device turns out
                // overridden still reserves. Decide after the run if that ever blocks a rule it should not.
                if (!event.isTest() && plan.willAttempt()) conflicts.reserve(rule);
            }
            return planned;
        });

        List<AutomationExecutionResponse> executions = new ArrayList<>();
        for (RulePlan plan : plans) executions.add(plan.result() != null ? plan.result() : run(plan));
        return AutomationEngineResponse.builder().evaluatedRules(evaluated[0]).matchedRules(executions.size())
                .executions(executions).build();
    }

    @Override
    public AutomationExecutionResponse executeScheduled(UUID homeId, UUID ruleId) {
        RulePlan plan = inTx(() -> {
            AutomationRule rule = findRule(homeId, ruleId);
            if (!rule.isEnabled() || rule.getTriggerType() != TriggerType.SCHEDULE) {
                throw new AppException(ErrorCode.AUTOMATION_TRIGGER_INVALID);
            }
            if (!rule.getConditions().isEmpty()) throw new AppException(ErrorCode.AUTOMATION_CONDITION_INVALID);
            ruleRepository.fetchActionsByIdIn(List.of(ruleId));
            return plan(rule, AutomationEventRequest.builder().eventType("SCHEDULE").data(Map.of()).build(), false);
        });
        return run(plan);
    }

    // ---------------------------------------------------------------- "true for X seconds"

    /** Runs the rule once its conditions stayed true for its hold time (no relevant non-matching event meanwhile). */
    private void startHold(UUID homeId, AutomationRule rule, AutomationEventRequest event) {
        UUID ruleId = rule.getId();
        OffsetDateTime version = rule.getUpdatedAt();
        long millis = rule.getHoldSeconds() * 1000L;
        @SuppressWarnings("unchecked")
        Future<?>[] self = new Future<?>[1];
        Runnable hold = () -> {
            try {
                sleeper.sleep(millis);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (self) {
                if (!pendingHolds.remove(ruleId, self[0])) return; // cancelled or replaced
            }
            try {
                RulePlan plan = inTx(() -> {
                    AutomationRule current = findRule(homeId, ruleId);
                    // Edited or switched off while counting: the old conditions no longer apply.
                    if (!current.isEnabled() || !java.util.Objects.equals(current.getUpdatedAt(), version)) return null;
                    ruleRepository.fetchActionsByIdIn(List.of(ruleId));
                    return plan(current, event, false);
                });
                if (plan != null) run(plan);
            } catch (RuntimeException failure) {
                log.warn("Held rule {} failed", ruleId, failure);
            }
        };
        synchronized (self) {
            self[0] = background.submit(hold);
            Future<?> previous = pendingHolds.put(ruleId, self[0]);
            if (previous != null) previous.cancel(true);
        }
    }

    private void cancelHold(UUID ruleId) {
        Future<?> pending = pendingHolds.remove(ruleId);
        if (pending == null) return;
        pending.cancel(true);
        // Nothing ran, so the cooldown taken when the hold started must not block the next hold.
        triggerGate.clearCooldown(ruleId);
    }

    // ---------------------------------------------------------------- running a rule

    /**
     * One rule's run, worked out inside the transaction; {@code result} is set when there is nothing left to run.
     * The steps are split into stages at each DELAY step: a stage starts after its wait and runs like a whole rule did.
     */
    private record RulePlan(AutomationRule rule, AutomationEventRequest event, OffsetDateTime startedAt, ArrayNode details,
                            List<Stage> stages, int waitRows, AutomationExecutionResponse result) {
        static RulePlan done(AutomationExecutionResponse result) {
            return new RulePlan(null, null, null, null, List.of(), 0, result);
        }

        boolean willAttempt() {
            return stages.stream().anyMatch(stage -> !stage.scenes().isEmpty() || !stage.commands().isEmpty());
        }

        boolean waits() {
            return stages.stream().anyMatch(stage -> stage.waitSeconds() > 0);
        }
    }

    private record SceneStep(UUID homeId, UUID sceneId, ObjectNode detail) {}

    /** Commands of one stage; {@code autoOffs} are undone that many seconds after their command succeeded. */
    private record Stage(int waitSeconds, ObjectNode waitDetail, List<SceneStep> scenes, List<PlannedCommand> commands,
                         List<AutoOff> autoOffs) {
        static Stage after(int waitSeconds, ObjectNode waitDetail) {
            return new Stage(waitSeconds, waitDetail, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private record AutoOff(PlannedCommand command, String undoAction, int seconds) {}

    /** Reads everything the run needs (lazy actions, scenes, groups, overrides) while the transaction is open. */
    private RulePlan plan(AutomationRule rule, AutomationEventRequest event, boolean emergency) {
        ArrayNode details = JsonNodeFactory.instance.arrayNode();
        List<RuleAction> steps = rule.getActions().stream().sorted(Comparator.comparingInt(RuleAction::getOrder)).toList();
        // One query for every device of the rule instead of one per action (each is a remote DB round trip).
        Set<UUID> overridden = event.isTest() ? Set.of() : manualOverrideService.getOverridesUntil(steps.stream()
                .flatMap(action -> targets(action).stream()).map(Device::getId).distinct().toList()).keySet();
        List<Stage> stages = new ArrayList<>();
        Stage stage = Stage.after(0, null);
        stages.add(stage);
        int waitRows = 0;
        for (RuleAction action : steps) {
            if (RuleTiming.DELAY.equals(action.getAction())) {
                ObjectNode wait = detailFor(action);
                details.add(wait);
                waitRows++;
                if (event.isTest()) {
                    CommandBatchRunner.skip(wait, "PREVIEW");
                } else {
                    stage = Stage.after(RuleTiming.delaySeconds(action.getParameters()), wait);
                    stages.add(stage);
                }
                continue;
            }
            if (action.getScene() != null) {
                ObjectNode detail = detailFor(action);
                details.add(detail);
                if (event.isTest()) {
                    CommandBatchRunner.skip(detail, "PREVIEW");
                } else if (action.getScene().isEnabled()) {
                    // Checked here so a disabled scene never reaches the scene service (which would throw SCENE_DISABLED).
                    stage.scenes().add(new SceneStep(rule.getHome().getId(), action.getScene().getId(), detail));
                } else {
                    CommandBatchRunner.skip(detail, "SKIPPED_DISABLED");
                }
                continue;
            }
            Map<String, Object> parameters = RuleTiming.commandParameters(action.getParameters());
            int autoOff = RuleTiming.autoOffSeconds(action.getParameters());
            String undo = autoOff > 0 ? RuleTiming.undoAction(action.getAction(), action.getParameters()) : null;
            for (Device device : targets(action)) {
                // A group step means "the members that can": e.g. the sensor in a group of lights is left out.
                if (action.getGroup() != null && !device.supportsAction(action.getAction())) continue;
                ObjectNode detail = detailFor(action);
                detail.put("deviceId", device.getId().toString());
                details.add(detail);
                if (event.isTest()) {
                    CommandBatchRunner.skip(detail, "PREVIEW");
                } else if (blockedByManualOverride(rule, device, event, emergency, detail, overridden)) {
                    CommandBatchRunner.skip(detail, "SKIPPED_OVERRIDE");
                } else {
                    PlannedCommand command = new PlannedCommand(device, action.getAction(), parameters, detail);
                    stage.commands().add(command);
                    if (undo != null && device.supportsAction(undo)) stage.autoOffs().add(new AutoOff(command, undo, autoOff));
                }
            }
        }
        return new RulePlan(rule, event, OffsetDateTime.now(), details, stages, waitRows, null);
    }

    /** The devices a step commands: its device, or every member of its group. */
    private static List<Device> targets(RuleAction action) {
        if (action.getGroup() != null) return List.copyOf(action.getGroup().getDevices());
        return action.getDevice() == null ? List.of() : List.of(action.getDevice());
    }

    /**
     * Sends the planned commands with no transaction open, then saves the result in a short one.
     * A rule with wait steps is saved as PENDING at once and finishes on a background thread.
     */
    private AutomationExecutionResponse run(RulePlan plan) {
        if (!plan.waits()) {
            int[] counts = runStage(plan.stages().getFirst());
            return finish(plan, counts[0], counts[1], null);
        }
        AutomationExecutionResponse pending = inTx(() -> save(plan.rule(), plan.event(), ExecutionStatus.PENDING,
                plan.details(), plan.startedAt()));
        background.execute(() -> {
            int attempted = 0;
            int succeeded = 0;
            for (Stage stage : plan.stages()) {
                if (stage.waitSeconds() > 0) {
                    try {
                        sleeper.sleep(stage.waitSeconds() * 1000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    stage.waitDetail().put("success", true);
                    stage.waitDetail().put("status", "WAITED");
                }
                int[] counts = runStage(stage);
                attempted += counts[0];
                succeeded += counts[1];
            }
            try {
                finish(plan, attempted, succeeded, pending.getId());
            } catch (RuntimeException failure) {
                log.warn("Could not save the result of rule {}", plan.rule().getId(), failure);
            }
        });
        return pending;
    }

    /** @return {attempted, succeeded} */
    private int[] runStage(Stage stage) {
        int attempted = stage.commands().size();
        int succeeded = 0;
        for (SceneStep scene : stage.scenes()) {
            SceneOutcome outcome = runScene(scene);
            if (outcome.attempted()) attempted++;
            if (outcome.succeeded()) succeeded++;
        }
        succeeded += commandBatchRunner.run(stage.commands(), StateChangeSource.AUTOMATION);
        for (AutoOff autoOff : stage.autoOffs()) {
            if (autoOff.command().detail().path("success").asBoolean(false)) scheduleAutoOff(autoOff);
        }
        return new int[]{attempted, succeeded};
    }

    /** Sends the undo command later, unless the user changed the device by hand in the meantime. */
    private void scheduleAutoOff(AutoOff autoOff) {
        Device device = autoOff.command().device();
        autoOff.command().detail().put("autoOffSeconds", autoOff.seconds());
        // shortcut: in memory, lost on a backend restart; persist pending auto-offs if that matters.
        background.execute(() -> {
            try {
                sleeper.sleep(autoOff.seconds() * 1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                if (!manualOverrideService.getOverridesUntil(List.of(device.getId())).isEmpty()) return;
                commandBatchRunner.run(List.of(new PlannedCommand(device, autoOff.undoAction(), Map.of(),
                        CommandBatchRunner.detail(device.getId(), autoOff.undoAction()))), StateChangeSource.AUTOMATION);
            } catch (RuntimeException failure) {
                log.warn("Auto-off of device {} failed", device.getId(), failure);
            }
        });
    }

    private AutomationExecutionResponse finish(RulePlan plan, int attempted, int succeeded, UUID pendingId) {
        AutomationRule rule = plan.rule();
        AutomationEventRequest event = plan.event();
        // Wait rows are not device results: leave them out of SUCCESS/PARTIAL.
        ExecutionStatus status = event.isTest() ? ExecutionStatus.SKIPPED
                : CommandBatchRunner.status(plan.details().size() - plan.waitRows(), attempted, succeeded);
        AutomationExecutionResponse response = inTx(() -> pendingId == null
                ? save(rule, event, status, plan.details(), plan.startedAt())
                : complete(pendingId, status, plan.details()));
        if (!event.isTest()) {
            try {
                behaviorEventRecorder.recordExecution(rule.getHome(), null, "AUTOMATION_EXECUTION", rule.getId(), response.getStatus());
            } catch (RuntimeException recordingFailure) {
                log.warn("Could not record automation execution {}", response.getId(), recordingFailure);
            }
        }
        return response;
    }

    private AutomationExecutionResponse complete(UUID executionId, ExecutionStatus status, ArrayNode details) {
        AutomationExecution execution = executionRepository.findById(executionId).orElseThrow();
        execution.setStatus(status);
        execution.setResultDetail(details);
        execution.setCompletedAt(OffsetDateTime.now());
        return AutomationRuleServiceImpl.toExecutionResponse(executionRepository.save(execution));
    }

    /** Runs {@code work} in its own short transaction; plain unit tests have no transaction manager. */
    private <T> T inTx(java.util.function.Supplier<T> work) {
        return transactionManager == null ? work.get() : new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private record SceneOutcome(boolean attempted, boolean succeeded) {}

    private SceneOutcome runScene(SceneStep step) {
        ObjectNode detail = step.detail();
        try {
            SceneExecutionResponse result = sceneExecutionService.executeFromAutomation(step.homeId(), step.sceneId());
            boolean success = "SUCCESS".equals(result.getStatus());
            detail.put("sceneExecutionId", result.getId().toString());
            detail.put("status", result.getStatus());
            detail.put("success", success);
            detail.set("resultDetail", result.getResultDetail());
            return new SceneOutcome(!"SKIPPED".equals(result.getStatus()), success);
        } catch (RuntimeException exception) {
            detail.put("message", "Scene execution failed");
            CommandBatchRunner.skip(detail, "FAILED");
            return new SceneOutcome(true, false);
        }
    }

    /** A device the user just changed by hand is left alone, except in an emergency. */
    private boolean blockedByManualOverride(AutomationRule rule, Device device, AutomationEventRequest event,
                                            boolean emergency, ObjectNode detail, Set<UUID> overridden) {
        if (!overridden.contains(device.getId())) return false;
        if (!emergency) return true;
        log.warn("[SAFETY BYPASS] Emergency event '{}' / rule '{}' bypassed manual override on device {}",
                event.getEventType(), rule.getName(), device.getId());
        detail.put("emergencyBypassed", true);
        return false;
    }

    private AutomationExecutionResponse saveSkippedConflict(AutomationRule rule, AutomationEventRequest event) {
        ArrayNode details = JsonNodeFactory.instance.arrayNode();
        rule.getActions().forEach(action -> details.add(CommandBatchRunner.skip(detailFor(action), "SKIPPED_CONFLICT")));
        return save(rule, event, ExecutionStatus.SKIPPED, details, OffsetDateTime.now());
    }

    private AutomationExecutionResponse save(AutomationRule rule, AutomationEventRequest event, ExecutionStatus status,
                                             ArrayNode details, OffsetDateTime startedAt) {
        AutomationExecution execution = AutomationExecution.builder().rule(rule).triggerSource(event.getEventType())
                .matchedAt(event.getOccurredAt() == null ? startedAt : event.getOccurredAt())
                .startedAt(startedAt).completedAt(OffsetDateTime.now()).status(status).test(event.isTest())
                .resultDetail(details).build();
        return AutomationRuleServiceImpl.toExecutionResponse(executionRepository.save(execution));
    }

    private static ObjectNode detailFor(RuleAction action) {
        ObjectNode detail = CommandBatchRunner.detail(action.getDevice() == null ? null : action.getDevice().getId(), action.getAction());
        if (action.getScene() != null) detail.put("sceneId", action.getScene().getId().toString());
        if (action.getGroup() != null) detail.put("groupId", action.getGroup().getId().toString());
        if (RuleTiming.DELAY.equals(action.getAction())) detail.put("seconds", RuleTiming.delaySeconds(action.getParameters()));
        return detail;
    }

    // ---------------------------------------------------------------- input checks

    private AutomationRule findRule(UUID homeId, UUID ruleId) {
        return ruleRepository.findByIdAndHomeId(ruleId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.AUTOMATION_RULE_NOT_FOUND));
    }

    /** @return the source device, or null when the event has none */
    private Device requireSourceDeviceInHome(UUID homeId, AutomationEventRequest event) {
        if (event == null || event.getEventType() == null || event.getEventType().isBlank() || event.getData() == null) {
            throw new AppException(ErrorCode.AUTOMATION_EVENT_INVALID);
        }
        if (event.getSourceDeviceId() == null) return null;
        Device device = deviceRepository.findById(event.getSourceDeviceId())
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        if (device.getRoom() == null || device.getRoom().getHome() == null
                || !homeId.equals(device.getRoom().getHome().getId())) {
            throw new AppException(ErrorCode.AUTOMATION_DEVICE_HOME_MISMATCH);
        }
        return device;
    }

    /** Decided by the event or the source device type, never by the user-editable rule name. */
    private static boolean isEmergency(AutomationEventRequest event, Device source) {
        if (hasEmergencyWord(event.getEventType())) return true;
        Object severity = event.getData().get("severity");
        if (severity != null && ("CRITICAL".equalsIgnoreCase(severity.toString()) || "EMERGENCY".equalsIgnoreCase(severity.toString()))) {
            return true;
        }
        if (Boolean.TRUE.equals(event.getData().get("emergency")) || Boolean.TRUE.equals(event.getData().get("isEmergency"))) {
            return true;
        }
        return source != null && hasEmergencyWord(source.getDeviceType());
    }

    private static boolean hasEmergencyWord(String value) {
        if (value == null) return false;
        for (String word : value.toUpperCase(Locale.ROOT).split("[^A-Z]+")) {
            if (EMERGENCY_WORDS.contains(word)) return true;
        }
        return false;
    }
}
