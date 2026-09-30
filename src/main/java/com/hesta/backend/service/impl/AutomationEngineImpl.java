package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.dto.response.AutomationEngineResponse;
import com.hesta.backend.dto.response.AutomationExecutionResponse;
import com.hesta.backend.dto.response.AutomationTestResponse;
import com.hesta.backend.dto.response.RuleActionResponse;
import com.hesta.backend.dto.response.SceneExecutionResponse;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.AutomationEngine;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.BehaviorEventRecorder;
import com.hesta.backend.service.ManualOverrideService;
import com.hesta.backend.service.SceneExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationEngineImpl implements AutomationEngine {
    private final AutomationRuleRepository ruleRepository;
    private final AutomationExecutionRepository executionRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceCommandService deviceCommandService;
    private final ObjectMapper objectMapper;
    private final BehaviorEventRecorder behaviorEventRecorder;
    private final ManualOverrideService manualOverrideService;
    private final SceneExecutionService sceneExecutionService;

    @Override
    @Transactional
    public AutomationEngineResponse process(UUID homeId, AutomationEventRequest event) {
        validateSourceDevice(homeId, event);
        if (event.getSourceDeviceId() != null && !event.isTest()) {
            deviceRepository.findById(event.getSourceDeviceId())
                    .ifPresent(device -> behaviorEventRecorder.recordSensor(device, event));
        }
        List<AutomationRule> rules = ruleRepository.findAllByHomeIdAndEnabledTrueAndTriggerTypeIn(
                homeId, List.of(TriggerType.SENSOR, TriggerType.EVENT));
        if (!rules.isEmpty()) {
            ruleRepository.fetchActionsByIdIn(rules.stream().map(AutomationRule::getId).toList());
        }
        List<AutomationExecutionResponse> executions = new ArrayList<>();
        Map<UUID, List<RuleAction>> reserved = new HashMap<>();
        for (AutomationRule rule : rules.stream().sorted(Comparator.comparing(AutomationRule::getId)).toList()) {
            if (!matches(rule, event)) continue;
            if (conflicts(rule, reserved)) {
                executions.add(skippedConflict(rule, event));
                continue;
            }
            AutomationExecutionResponse outcome = execute(rule, event);
            executions.add(outcome);
            if (!event.isTest() && !"SKIPPED".equals(outcome.getStatus())) {
                for (RuleAction action : rule.getActions()) {
                    for (RuleAction target : targetActions(action)) {
                        reserved.computeIfAbsent(target.getDevice().getId(), ignored -> new ArrayList<>()).add(target);
                    }
                }
            }
        }
        return AutomationEngineResponse.builder().evaluatedRules(rules.size()).matchedRules(executions.size())
                .executions(executions).build();
    }

    private boolean conflicts(AutomationRule rule, Map<UUID, List<RuleAction>> reserved) {
        for (RuleAction action : rule.getActions()) {
            for (RuleAction target : targetActions(action)) {
                for (RuleAction earlier : reserved.getOrDefault(target.getDevice().getId(), List.of())) {
                    if (conflicts(target, earlier)) return true;
                }
            }
        }
        return false;
    }

    private List<RuleAction> targetActions(RuleAction action) {
        if (action.getScene() == null) return List.of(action);
        return action.getScene().getActions().stream().map(item -> RuleAction.builder()
                .device(item.getTargetDevice()).action("TURN_ON".equals(item.getAction()) ? "TURN_ON"
                        : "TURN_OFF".equals(item.getAction()) ? "TURN_OFF" : "SET_STATE")
                .build()).toList();
    }

    private boolean conflicts(RuleAction left, RuleAction right) {
        if ("SET_STATE".equals(left.getAction()) || "SET_STATE".equals(right.getAction())) return true;
        boolean leftPower = List.of("TURN_ON", "TURN_OFF", "TOGGLE").contains(left.getAction());
        boolean rightPower = List.of("TURN_ON", "TURN_OFF", "TOGGLE").contains(right.getAction());
        if (leftPower && rightPower) return left.getAction() != right.getAction()
                || "TOGGLE".equals(left.getAction());
        return left.getAction() == right.getAction() && !Objects.equals(left.getParameters(), right.getParameters());
    }

    private AutomationExecutionResponse skippedConflict(AutomationRule rule, AutomationEventRequest event) {
        OffsetDateTime now = OffsetDateTime.now();
        ArrayNode details = objectMapper.createArrayNode();
        for (RuleAction action : rule.getActions()) {
            ObjectNode detail = objectMapper.createObjectNode();
            if (action.getDevice() != null) detail.put("deviceId", action.getDevice().getId().toString());
            if (action.getScene() != null) detail.put("sceneId", action.getScene().getId().toString());
            detail.put("action", action.getAction());
            detail.put("success", false);
            detail.put("status", "SKIPPED_CONFLICT");
            details.add(detail);
        }
        AutomationExecution execution = AutomationExecution.builder().rule(rule).triggerSource(event.getEventType())
                .matchedAt(event.getOccurredAt() == null ? now : event.getOccurredAt())
                .startedAt(now).completedAt(now).status(ExecutionStatus.SKIPPED).test(event.isTest())
                .resultDetail(details).build();
        return AutomationRuleServiceImpl.toExecutionResponse(executionRepository.save(execution));
    }

    @Override
    @Transactional(readOnly = true)
    public AutomationTestResponse testRule(UUID homeId, UUID ruleId, AutomationEventRequest event) {
        validateSourceDevice(homeId, event);
        AutomationRule rule = ruleRepository.findByIdAndHomeId(ruleId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.AUTOMATION_RULE_NOT_FOUND));
        if (rule.getTriggerType() == TriggerType.SCHEDULE && !rule.getConditions().isEmpty()) {
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
        return AutomationTestResponse.builder().ruleId(ruleId)
                .matched(rule.getTriggerType() == TriggerType.SCHEDULE || matches(rule, event))
                .proposedActions(preview).build();
    }

    @Override
    @Transactional
    public AutomationExecutionResponse executeScheduled(UUID homeId, UUID ruleId) {
        AutomationRule rule = ruleRepository.findByIdAndHomeId(ruleId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.AUTOMATION_RULE_NOT_FOUND));
        if (!rule.isEnabled() || rule.getTriggerType() != TriggerType.SCHEDULE) {
            throw new AppException(ErrorCode.AUTOMATION_TRIGGER_INVALID);
        }
        if (!rule.getConditions().isEmpty()) throw new AppException(ErrorCode.AUTOMATION_CONDITION_INVALID);
        ruleRepository.fetchActionsByIdIn(List.of(ruleId));
        return execute(rule, AutomationEventRequest.builder().eventType("SCHEDULE").data(Map.of()).build());
    }

    private void validateSourceDevice(UUID homeId, AutomationEventRequest event) {
        if (event == null || event.getEventType() == null || event.getEventType().isBlank() || event.getData() == null) {
            throw new AppException(ErrorCode.AUTOMATION_EVENT_INVALID);
        }
        if (event.getSourceDeviceId() == null) return;
        Device device = deviceRepository.findById(event.getSourceDeviceId())
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        if (!homeId.equals(device.getNode().getHome().getId())) {
            throw new AppException(ErrorCode.AUTOMATION_DEVICE_HOME_MISMATCH);
        }
    }

    private boolean matches(AutomationRule rule, AutomationEventRequest event) {
        List<RuleCondition> conditions = rule.getConditions().stream()
                .sorted(Comparator.comparingInt(RuleCondition::getOrder)).toList();
        if (conditions.isEmpty()) return false;
        boolean result = evaluate(conditions.getFirst(), event);
        for (int index = 1; index < conditions.size(); index++) {
            RuleCondition condition = conditions.get(index);
            boolean current = evaluate(condition, event);
            result = condition.getLogicalOperator() == LogicalOperator.OR ? result || current : result && current;
        }
        return result;
    }

    private boolean evaluate(RuleCondition condition, AutomationEventRequest event) {
        if (condition.getDevice() != null && !condition.getDevice().getId().equals(event.getSourceDeviceId())) return false;
        Object actual = "eventType".equals(condition.getAttribute())
                ? event.getEventType() : event.getData().get(condition.getAttribute());
        if (actual == null) return false;
        Object expected = normalizeJson(condition.getExpectedValue());
        actual = normalizeJson(actual);
        int comparison = compare(actual, expected);
        return switch (condition.getOperator()) {
            case EQ -> comparison == 0;
            case NE -> comparison != 0;
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            default -> false;
        };
    }

    private Object normalizeJson(Object value) {
        if (value instanceof JsonNode node) return objectMapper.convertValue(node, Object.class);
        return value;
    }

    private int compare(Object actual, Object expected) {
        if (actual instanceof Number left && expected instanceof Number right) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
        }
        if (actual instanceof Boolean left && expected instanceof Boolean right) return left.compareTo(right);
        return String.valueOf(actual).compareToIgnoreCase(String.valueOf(expected));
    }

    private AutomationExecutionResponse execute(AutomationRule rule, AutomationEventRequest event) {
        OffsetDateTime now = OffsetDateTime.now();
        ArrayNode details = objectMapper.createArrayNode();
        int successes = 0;
        int attempted = 0;
        int total = rule.getActions().size();
        for (RuleAction action : rule.getActions().stream().sorted(Comparator.comparingInt(RuleAction::getOrder)).toList()) {
            ObjectNode detail = objectMapper.createObjectNode();
            if (action.getDevice() != null) detail.put("deviceId", action.getDevice().getId().toString());
            if (action.getScene() != null) detail.put("sceneId", action.getScene().getId().toString());
            detail.put("action", action.getAction());
            if (event.isTest()) {
                detail.put("success", false);
                detail.put("status", "PREVIEW");
                details.add(detail);
                continue;
            }
            if (action.getScene() != null) {
                try {
                    SceneExecutionResponse result = sceneExecutionService.executeFromAutomation(rule.getHome().getId(), action.getScene().getId());
                    if (!"SKIPPED".equals(result.getStatus())) attempted++;
                    detail.put("sceneExecutionId", result.getId().toString());
                    detail.put("status", result.getStatus());
                    detail.put("success", "SUCCESS".equals(result.getStatus()));
                    detail.set("resultDetail", result.getResultDetail());
                    if ("SUCCESS".equals(result.getStatus())) successes++;
                } catch (RuntimeException exception) {
                    attempted++;
                    detail.put("success", false);
                    detail.put("status", "FAILED");
                    detail.put("message", "Scene execution failed");
                }
                details.add(detail);
                continue;
            }
            if (manualOverrideService.isActive(action.getDevice().getId())) {
                detail.put("success", false);
                detail.put("status", "SKIPPED_OVERRIDE");
                details.add(detail);
                continue;
            }
            attempted++;
            try {
                CommandResult result = deviceCommandService.sendCommand(action.getDevice().getId(), action.getAction(),
                        action.getParameters(), StateChangeSource.AUTOMATION).join();
                detail.put("success", result.isSuccess());
                detail.put("status", result.getStatus());
                if (result.getCommandId() != null) detail.put("commandId", result.getCommandId());
                if (result.getErrorCode() != null) detail.put("errorCode", result.getErrorCode());
                if (result.getMessage() != null) detail.put("message", result.getMessage());
                if (result.isSuccess()) {
                    successes++;
                    try {
                        behaviorEventRecorder.recordCommand(action.getDevice(), action.getAction(), StateChangeSource.AUTOMATION, result);
                    } catch (RuntimeException recordingFailure) {
                        log.warn("Could not record automation command for device {}", action.getDevice().getId(), recordingFailure);
                    }
                }
            } catch (RuntimeException exception) {
                detail.put("success", false);
                detail.put("status", "FAILED");
                detail.put("message", "Device command failed");
            }
            details.add(detail);
        }
        ExecutionStatus status = event.isTest() || attempted == 0 ? ExecutionStatus.SKIPPED
                : successes == total ? ExecutionStatus.SUCCESS
                : successes == 0 ? ExecutionStatus.FAILED : ExecutionStatus.PARTIAL;
        AutomationExecution execution = AutomationExecution.builder().rule(rule)
                .triggerSource(event.getEventType()).matchedAt(event.getOccurredAt() == null ? now : event.getOccurredAt())
                .startedAt(now).completedAt(OffsetDateTime.now()).status(status).test(event.isTest())
                .resultDetail(details).build();
        AutomationExecutionResponse response = AutomationRuleServiceImpl.toExecutionResponse(executionRepository.save(execution));
        if (!event.isTest()) {
            try {
                behaviorEventRecorder.recordExecution(rule.getHome(), null, "AUTOMATION_EXECUTION", rule.getId(), response.getStatus());
            } catch (RuntimeException recordingFailure) {
                log.warn("Could not record automation execution {}", response.getId(), recordingFailure);
            }
        }
        return response;
    }
}
