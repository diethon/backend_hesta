package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.dto.response.AutomationEngineResponse;
import com.hesta.backend.dto.response.AutomationExecutionResponse;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.AutomationEngine;
import com.hesta.backend.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AutomationEngineImpl implements AutomationEngine {
    private final AutomationRuleRepository ruleRepository;
    private final AutomationExecutionRepository executionRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceCommandService deviceCommandService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public AutomationEngineResponse process(UUID homeId, AutomationEventRequest event) {
        validateSourceDevice(homeId, event);
        List<AutomationRule> rules = ruleRepository.findAllByHomeIdAndEnabledTrueAndTriggerTypeIn(
                homeId, List.of(TriggerType.SENSOR, TriggerType.EVENT));
        if (!rules.isEmpty()) {
            ruleRepository.fetchActionsByIdIn(rules.stream().map(AutomationRule::getId).toList());
        }
        List<AutomationExecutionResponse> executions = new ArrayList<>();
        for (AutomationRule rule : rules) {
            if (!matches(rule, event)) continue;
            executions.add(execute(rule, event));
        }
        return AutomationEngineResponse.builder().evaluatedRules(rules.size()).matchedRules(executions.size())
                .executions(executions).build();
    }

    private void validateSourceDevice(UUID homeId, AutomationEventRequest event) {
        if (event == null || event.getEventType() == null || event.getEventType().isBlank() || event.getData() == null) {
            throw new AppException(ErrorCode.AUTOMATION_EVENT_INVALID);
        }
        if (event.getSourceDeviceId() == null) return;
        Device device = deviceRepository.findById(event.getSourceDeviceId())
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        if (!homeId.equals(device.getHome().getId())) {
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
        int total = rule.getActions().size();
        for (RuleAction action : rule.getActions().stream().sorted(Comparator.comparingInt(RuleAction::getOrder)).toList()) {
            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("deviceId", action.getDevice().getId().toString());
            detail.put("action", action.getAction().name());
            try {
                CommandResult result = deviceCommandService.sendCommand(action.getDevice().getId(), action.getAction(),
                        action.getParameters(), StateChangeSource.AUTOMATION).join();
                detail.put("success", result.isSuccess());
                detail.put("status", result.getStatus());
                if (result.getCommandId() != null) detail.put("commandId", result.getCommandId());
                if (result.getErrorCode() != null) detail.put("errorCode", result.getErrorCode());
                if (result.getMessage() != null) detail.put("message", result.getMessage());
                if (result.isSuccess()) successes++;
            } catch (RuntimeException exception) {
                detail.put("success", false);
                detail.put("status", "FAILED");
                detail.put("message", "Device command failed");
            }
            details.add(detail);
        }
        ExecutionStatus status = successes == total ? ExecutionStatus.SUCCESS
                : successes == 0 ? ExecutionStatus.FAILED : ExecutionStatus.PARTIAL;
        AutomationExecution execution = AutomationExecution.builder().rule(rule)
                .triggerSource(event.getEventType()).matchedAt(event.getOccurredAt() == null ? now : event.getOccurredAt())
                .startedAt(now).completedAt(OffsetDateTime.now()).status(status).test(event.isTest())
                .resultDetail(details).build();
        return AutomationRuleServiceImpl.toExecutionResponse(executionRepository.save(execution));
    }
}
