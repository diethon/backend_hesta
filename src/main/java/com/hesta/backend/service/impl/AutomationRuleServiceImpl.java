package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.*;
import com.hesta.backend.dto.response.*;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.AutomationRuleService;
import com.hesta.backend.service.HomeAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class AutomationRuleServiceImpl implements AutomationRuleService {
    private final HomeAuthorizationService homeAuthorizationService;
    private final AutomationRuleRepository ruleRepository;
    private final AutomationExecutionRepository executionRepository;
    private final DeviceRepository deviceRepository;
    private final SceneRepository sceneRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public AutomationRuleResponse create(UUID userId, UUID homeId, CreateAutomationRuleRequest request) {
        Home home = homeAuthorizationService.requireSceneManagement(userId, homeId);
        validateName(request.getName(), homeId, null);
        TriggerType triggerType = parseTrigger(request.getTriggerType());
        AutomationRule rule = AutomationRule.builder()
                .home(home).name(request.getName().trim()).description(normalize(request.getDescription()))
                .triggerType(triggerType).enabled(request.getEnabled()).build();
        replaceChildren(rule, request.getConditions(), request.getActions(), home);
        return toResponse(ruleRepository.saveAndFlush(rule));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutomationRuleResponse> getAll(UUID userId, UUID homeId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        List<AutomationRule> rules = ruleRepository.findAllByHomeIdOrderByNameAsc(homeId);
        fetchActions(rules);
        return rules.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AutomationRuleResponse get(UUID userId, UUID homeId, UUID ruleId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        return toResponse(find(ruleId, homeId));
    }

    @Override
    @Transactional
    public AutomationRuleResponse update(UUID userId, UUID homeId, UUID ruleId, UpdateAutomationRuleRequest request) {
        Home home = homeAuthorizationService.requireSceneManagement(userId, homeId);
        AutomationRule rule = find(ruleId, homeId);
        validateName(request.getName(), homeId, ruleId);
        rule.setName(request.getName().trim());
        rule.setDescription(normalize(request.getDescription()));
        rule.setTriggerType(parseTrigger(request.getTriggerType()));
        rule.setEnabled(request.getEnabled());
        replaceChildren(rule, request.getConditions(), request.getActions(), home);
        return toResponse(ruleRepository.saveAndFlush(rule));
    }

    @Override
    @Transactional
    public AutomationRuleResponse setEnabled(UUID userId, UUID homeId, UUID ruleId, boolean enabled) {
        homeAuthorizationService.requireSceneManagement(userId, homeId);
        AutomationRule rule = find(ruleId, homeId);
        rule.setEnabled(enabled);
        return toResponse(ruleRepository.saveAndFlush(rule));
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID homeId, UUID ruleId) {
        homeAuthorizationService.requireSceneManagement(userId, homeId);
        ruleRepository.delete(find(ruleId, homeId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutomationExecutionResponse> getExecutions(UUID userId, UUID homeId, UUID ruleId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        AutomationRule rule = find(ruleId, homeId);
        return executionRepository.findTop100ByRuleIdOrderByMatchedAtDesc(rule.getId()).stream()
                .map(AutomationRuleServiceImpl::toExecutionResponse).toList();
    }

    private void replaceChildren(AutomationRule rule, List<RuleConditionRequest> conditionRequests,
                                 List<RuleActionRequest> actionRequests, Home home) {
        if (conditionRequests == null || (conditionRequests.isEmpty() && rule.getTriggerType() != TriggerType.SCHEDULE)) {
            throw new AppException(ErrorCode.AUTOMATION_CONDITION_INVALID);
        }
        if (rule.getTriggerType() == TriggerType.SCHEDULE && !conditionRequests.isEmpty()) {
            throw new AppException(ErrorCode.AUTOMATION_CONDITION_INVALID);
        }
        if (!conditionRequests.isEmpty()) {
            validateOrders(conditionRequests.stream().map(RuleConditionRequest::getOrder).toList(), ErrorCode.AUTOMATION_CONDITION_INVALID);
        }
        validateOrders(actionRequests.stream().map(RuleActionRequest::getOrder).toList(), ErrorCode.AUTOMATION_ACTION_INVALID);
        Map<UUID, Device> devices = loadDevices(conditionRequests, actionRequests);

        List<RuleCondition> conditions = conditionRequests.stream()
                .sorted(Comparator.comparingInt(RuleConditionRequest::getOrder))
                .map(request -> buildCondition(rule, request, devices, home)).toList();
        List<RuleAction> actions = actionRequests.stream()
                .sorted(Comparator.comparingInt(RuleActionRequest::getOrder))
                .map(request -> buildAction(rule, request, devices, home)).toList();

        rule.getConditions().clear();
        rule.getActions().clear();
        if (rule.getId() != null) {
            // Delete old rows before inserting replacement rows with the same order indexes.
            ruleRepository.saveAndFlush(rule);
        }
        rule.getConditions().addAll(conditions);
        rule.getActions().addAll(actions);
    }

    private RuleCondition buildCondition(AutomationRule rule, RuleConditionRequest request,
                                         Map<UUID, Device> devices, Home home) {
        if (request == null || request.getAttribute() == null || request.getAttribute().isBlank()
                || request.getExpectedValue() == null) {
            throw new AppException(ErrorCode.AUTOMATION_CONDITION_INVALID);
        }
        ConditionOperator operator = ConditionOperator.from(request.getOperator())
                .orElseThrow(() -> new AppException(ErrorCode.AUTOMATION_CONDITION_INVALID));
        LogicalOperator logicalOperator;
        try {
            logicalOperator = LogicalOperator.valueOf(request.getLogicalOperator().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new AppException(ErrorCode.AUTOMATION_CONDITION_INVALID);
        }
        Device device = request.getDeviceId() == null ? null : devices.get(request.getDeviceId());
        validateHome(device, home);
        return RuleCondition.builder().rule(rule).device(device).attribute(request.getAttribute().trim())
                .operator(operator).expectedValue(objectMapper.valueToTree(request.getExpectedValue()))
                .logicalOperator(logicalOperator)
                .order(request.getOrder()).build();
    }

    private RuleAction buildAction(AutomationRule rule, RuleActionRequest request,
                                   Map<UUID, Device> devices, Home home) {
        if (request == null || request.getAction() == null) {
            throw new AppException(ErrorCode.AUTOMATION_ACTION_INVALID);
        }
        DeviceAction action;
        try {
            action = DeviceAction.valueOf(request.getAction().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new AppException(ErrorCode.AUTOMATION_ACTION_INVALID);
        }
        if (action == DeviceAction.EXECUTE_SCENE) {
            if (request.getSceneId() == null || request.getDeviceId() != null
                    || (request.getParameters() != null && !request.getParameters().isEmpty())) {
                throw new AppException(ErrorCode.AUTOMATION_ACTION_INVALID);
            }
            Scene scene = sceneRepository.findByIdAndHomeId(request.getSceneId(), home.getId())
                    .orElseThrow(() -> new AppException(ErrorCode.SCENE_NOT_FOUND));
            if (!scene.isEnabled() || scene.getActions().isEmpty()) {
                throw new AppException(ErrorCode.AUTOMATION_ACTION_INVALID);
            }
            return RuleAction.builder().rule(rule).scene(scene).action(action).order(request.getOrder()).build();
        }
        if (request.getDeviceId() == null || request.getSceneId() != null) {
            throw new AppException(ErrorCode.AUTOMATION_ACTION_INVALID);
        }
        Device device = devices.get(request.getDeviceId());
        validateHome(device, home);
        validateActionParameters(action, request.getParameters());
        if (device.getCapabilities() != null && !device.getCapabilities().isEmpty()
                && device.getCapabilities().stream().noneMatch(capability -> capability.equalsIgnoreCase(action.name()))) {
            throw new AppException(ErrorCode.AUTOMATION_ACTION_INVALID);
        }
        return RuleAction.builder().rule(rule).device(device).action(action)
                .parameters(request.getParameters() == null ? new HashMap<>() : new HashMap<>(request.getParameters()))
                .order(request.getOrder()).build();
    }

    private void validateActionParameters(DeviceAction action, Map<String, Object> parameters) {
        Map<String, Object> values = parameters == null ? Map.of() : parameters;
        boolean valid = switch (action) {
            case TURN_ON, TURN_OFF, TOGGLE -> true;
            case SET_BRIGHTNESS -> percentage(values.get("level"));
            case SET_SPEED -> percentage(values.get("speed"));
            case SET_TEMPERATURE -> values.get("temperature") instanceof Number;
            case SET_MODE -> values.get("mode") instanceof String mode && !mode.isBlank();
            case SET_STATE -> !values.isEmpty();
            case EXECUTE_SCENE -> false;
        };
        if (!valid) throw new AppException(ErrorCode.AUTOMATION_ACTION_INVALID);
    }

    private boolean percentage(Object value) {
        return value instanceof Number number && number.doubleValue() >= 0 && number.doubleValue() <= 100;
    }

    private Map<UUID, Device> loadDevices(List<RuleConditionRequest> conditions, List<RuleActionRequest> actions) {
        Set<UUID> ids = new HashSet<>();
        conditions.stream().map(RuleConditionRequest::getDeviceId).filter(Objects::nonNull).forEach(ids::add);
        actions.stream().map(RuleActionRequest::getDeviceId).filter(Objects::nonNull).forEach(ids::add);
        Map<UUID, Device> result = new HashMap<>();
        deviceRepository.findAllById(ids).forEach(device -> result.put(device.getId(), device));
        if (result.size() != ids.size()) throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        return result;
    }

    private void validateHome(Device device, Home home) {
        if (device == null) return;
        if (device.getRoom() == null || device.getRoom().getHome() == null
                || !home.getId().equals(device.getRoom().getHome().getId())) {
            throw new AppException(ErrorCode.AUTOMATION_DEVICE_HOME_MISMATCH);
        }
    }

    private void validateOrders(List<Integer> orders, ErrorCode errorCode) {
        if (orders.isEmpty() || orders.stream().anyMatch(Objects::isNull)
                || new HashSet<>(orders).size() != orders.size()) throw new AppException(errorCode);
        for (int index = 0; index < orders.size(); index++) {
            if (!orders.contains(index)) throw new AppException(errorCode);
        }
    }

    private void validateName(String name, UUID homeId, UUID excludedId) {
        if (name == null || name.isBlank() || name.trim().length() > 150) {
            throw new AppException(ErrorCode.AUTOMATION_NAME_INVALID);
        }
        boolean exists = excludedId == null ? ruleRepository.existsByHomeIdAndName(homeId, name.trim())
                : ruleRepository.existsByHomeIdAndNameAndIdNot(homeId, name.trim(), excludedId);
        if (exists) throw new AppException(ErrorCode.AUTOMATION_NAME_ALREADY_EXISTS);
    }

    private TriggerType parseTrigger(String raw) {
        try {
            return TriggerType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new AppException(ErrorCode.AUTOMATION_TRIGGER_INVALID);
        }
    }

    private AutomationRule find(UUID id, UUID homeId) {
        AutomationRule rule = ruleRepository.findByIdAndHomeId(id, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.AUTOMATION_RULE_NOT_FOUND));
        fetchActions(List.of(rule));
        return rule;
    }

    private void fetchActions(List<AutomationRule> rules) {
        if (!rules.isEmpty()) {
            ruleRepository.fetchActionsByIdIn(rules.stream().map(AutomationRule::getId).toList());
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AutomationRuleResponse toResponse(AutomationRule rule) {
        return AutomationRuleResponse.builder().id(rule.getId()).homeId(rule.getHome().getId())
                .name(rule.getName()).description(rule.getDescription()).triggerType(rule.getTriggerType().name())
                .enabled(rule.isEnabled()).createdAt(rule.getCreatedAt()).updatedAt(rule.getUpdatedAt())
                .conditions(rule.getConditions().stream().sorted(Comparator.comparingInt(RuleCondition::getOrder))
                        .map(condition -> RuleConditionResponse.builder().id(condition.getId())
                                .deviceId(condition.getDevice() == null ? null : condition.getDevice().getId())
                                .deviceName(condition.getDevice() == null ? null : condition.getDevice().getName())
                                .attribute(condition.getAttribute()).operator(condition.getOperator().name())
                                .expectedValue(condition.getExpectedValue())
                                .logicalOperator(condition.getLogicalOperator().name()).order(condition.getOrder()).build()).toList())
                .actions(rule.getActions().stream().sorted(Comparator.comparingInt(RuleAction::getOrder))
                        .map(action -> RuleActionResponse.builder().id(action.getId())
                                .deviceId(action.getDevice() == null ? null : action.getDevice().getId())
                                .deviceName(action.getDevice() == null ? null : action.getDevice().getName())
                                .sceneId(action.getScene() == null ? null : action.getScene().getId())
                                .sceneName(action.getScene() == null ? null : action.getScene().getName())
                                .action(action.getAction().name())
                                .parameters(action.getParameters()).order(action.getOrder()).build()).toList())
                .build();
    }

    public static AutomationExecutionResponse toExecutionResponse(AutomationExecution execution) {
        return AutomationExecutionResponse.builder().id(execution.getId()).ruleId(execution.getRule().getId())
                .ruleName(execution.getRule().getName()).triggerSource(execution.getTriggerSource())
                .status(execution.getStatus().name()).test(execution.isTest()).matchedAt(execution.getMatchedAt())
                .startedAt(execution.getStartedAt()).completedAt(execution.getCompletedAt())
                .resultDetail(execution.getResultDetail()).build();
    }
}
