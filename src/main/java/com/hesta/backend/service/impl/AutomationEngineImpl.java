package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.dto.response.AutomationEngineResponse;
import com.hesta.backend.dto.response.AutomationExecutionResponse;
import com.hesta.backend.dto.response.AutomationTestResponse;
import com.hesta.backend.dto.response.RuleActionResponse;
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
import com.hesta.backend.service.automation.RuleTriggerGate;
import com.hesta.backend.service.execution.CommandBatchRunner;
import com.hesta.backend.service.execution.CommandBatchRunner.PlannedCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    @Override
    @Transactional
    public AutomationEngineResponse process(UUID homeId, AutomationEventRequest event) {
        Device source = requireSourceDeviceInHome(homeId, event);
        if (source != null && !event.isTest()) behaviorEventRecorder.recordSensor(source, event);

        List<AutomationRule> rules = ruleRepository.findAllByHomeIdAndEnabledTrueAndTriggerTypeIn(
                homeId, List.of(TriggerType.SENSOR, TriggerType.EVENT));
        if (!rules.isEmpty()) ruleRepository.fetchActionsByIdIn(rules.stream().map(AutomationRule::getId).toList());

        boolean emergency = isEmergency(event, source);
        ActionConflictDetector conflicts = new ActionConflictDetector();
        List<AutomationExecutionResponse> executions = new ArrayList<>();
        for (AutomationRule rule : rules.stream().sorted(OLDEST_FIRST).toList()) {
            boolean matched = ruleMatcher.matches(rule, event);
            // Claimed before running, so a conflict skip also waits for the next edge (no SKIPPED row on every report).
            if (!triggerGate.tryClaim(rule.getId(), event, matched, emergency)) continue;
            if (conflicts.conflictsWithReserved(rule)) {
                executions.add(saveSkippedConflict(rule, event));
                continue;
            }
            AutomationExecutionResponse outcome = execute(rule, event, emergency);
            executions.add(outcome);
            if (!event.isTest() && !"SKIPPED".equals(outcome.getStatus())) conflicts.reserve(rule);
        }
        return AutomationEngineResponse.builder().evaluatedRules(rules.size()).matchedRules(executions.size())
                .executions(executions).build();
    }

    @Override
    @Transactional(readOnly = true)
    public AutomationTestResponse testRule(UUID homeId, UUID ruleId, AutomationEventRequest event) {
        requireSourceDeviceInHome(homeId, event);
        AutomationRule rule = findRule(homeId, ruleId);
        if (rule.getTriggerType() == TriggerType.SCHEDULE && !rule.getConditions().isEmpty()) {
            log.warn("testRule {}: SCHEDULE rule has {} condition(s), data integrity error", ruleId, rule.getConditions().size());
            throw new AppException(ErrorCode.AUTOMATION_CONDITION_INVALID);
        }
        ruleRepository.fetchActionsByIdIn(List.of(ruleId));
        List<RuleActionResponse> preview = rule.getActions().stream()
                .sorted(Comparator.comparingInt(RuleAction::getOrder))
                .map(action -> RuleActionResponse.builder().id(action.getId())
                        .deviceId(action.getDevice() == null ? null : action.getDevice().getId())
                        .deviceName(action.getDevice() == null ? null : action.getDevice().getName())
                        .sceneId(action.getScene() == null ? null : action.getScene().getId())
                        .sceneName(action.getScene() == null ? null : action.getScene().getName())
                        .action(action.getAction())
                        .parameters(action.getParameters()).order(action.getOrder()).build())
                .toList();
        boolean matched = rule.getTriggerType() == TriggerType.SCHEDULE || ruleMatcher.matches(rule, event);
        return AutomationTestResponse.builder().ruleId(ruleId).matched(matched).proposedActions(preview).build();
    }

    @Override
    @Transactional
    public AutomationExecutionResponse executeScheduled(UUID homeId, UUID ruleId) {
        AutomationRule rule = findRule(homeId, ruleId);
        if (!rule.isEnabled() || rule.getTriggerType() != TriggerType.SCHEDULE) {
            throw new AppException(ErrorCode.AUTOMATION_TRIGGER_INVALID);
        }
        if (!rule.getConditions().isEmpty()) throw new AppException(ErrorCode.AUTOMATION_CONDITION_INVALID);
        ruleRepository.fetchActionsByIdIn(List.of(ruleId));
        return execute(rule, AutomationEventRequest.builder().eventType("SCHEDULE").data(Map.of()).build(), false);
    }

    // ---------------------------------------------------------------- running a rule

    private AutomationExecutionResponse execute(AutomationRule rule, AutomationEventRequest event, boolean emergency) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        ArrayNode details = JsonNodeFactory.instance.arrayNode();
        List<PlannedCommand> deviceCommands = new ArrayList<>();
        int attempted = 0;
        int succeeded = 0;

        for (RuleAction action : rule.getActions().stream().sorted(Comparator.comparingInt(RuleAction::getOrder)).toList()) {
            ObjectNode detail = detailFor(action);
            details.add(detail);
            if (event.isTest()) {
                CommandBatchRunner.skip(detail, "PREVIEW");
            } else if (action.getScene() != null) {
                SceneOutcome outcome = runScene(rule, action, detail);
                if (outcome.attempted()) attempted++;
                if (outcome.succeeded()) succeeded++;
            } else if (blockedByManualOverride(rule, action, event, emergency, detail)) {
                CommandBatchRunner.skip(detail, "SKIPPED_OVERRIDE");
            } else {
                attempted++;
                deviceCommands.add(new PlannedCommand(action.getDevice(), action.getAction(), action.getParameters(), detail));
            }
        }
        succeeded += commandBatchRunner.run(deviceCommands, StateChangeSource.AUTOMATION);

        ExecutionStatus status = event.isTest() ? ExecutionStatus.SKIPPED
                : CommandBatchRunner.status(details.size(), attempted, succeeded);
        AutomationExecutionResponse response = save(rule, event, status, details, startedAt);
        if (!event.isTest()) {
            try {
                behaviorEventRecorder.recordExecution(rule.getHome(), null, "AUTOMATION_EXECUTION", rule.getId(), response.getStatus());
            } catch (RuntimeException recordingFailure) {
                log.warn("Could not record automation execution {}", response.getId(), recordingFailure);
            }
        }
        return response;
    }

    private record SceneOutcome(boolean attempted, boolean succeeded) {}

    private SceneOutcome runScene(AutomationRule rule, RuleAction action, ObjectNode detail) {
        // Checked here: if the scene service threw SCENE_DISABLED it would mark this whole transaction rollback-only.
        if (!action.getScene().isEnabled()) {
            CommandBatchRunner.skip(detail, "SKIPPED_DISABLED");
            return new SceneOutcome(false, false);
        }
        try {
            SceneExecutionResponse result = sceneExecutionService.executeFromAutomation(rule.getHome().getId(), action.getScene().getId());
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
    private boolean blockedByManualOverride(AutomationRule rule, RuleAction action, AutomationEventRequest event,
                                            boolean emergency, ObjectNode detail) {
        if (!manualOverrideService.isActive(action.getDevice().getId())) return false;
        if (!emergency) return true;
        log.warn("[SAFETY BYPASS] Emergency event '{}' / rule '{}' bypassed manual override on device {}",
                event.getEventType(), rule.getName(), action.getDevice().getId());
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
