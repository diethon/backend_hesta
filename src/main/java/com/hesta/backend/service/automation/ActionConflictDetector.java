package com.hesta.backend.service.automation;

import com.hesta.backend.entity.AutomationRule;
import com.hesta.backend.entity.RuleAction;
import com.hesta.backend.service.execution.DeviceActionValues;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Within one event, the first rule that runs "reserves" its devices; a later rule that would send an
 * opposite command to one of them (ON vs OFF, OPEN vs CLOSE, different values) is skipped.
 * Create one per event.
 */
public class ActionConflictDetector {
    private final Map<UUID, List<RuleAction>> reserved = new HashMap<>();

    public boolean conflictsWithReserved(AutomationRule rule) {
        for (RuleAction action : deviceActions(rule)) {
            for (RuleAction earlier : reserved.getOrDefault(action.getDevice().getId(), List.of())) {
                if (opposite(action, earlier)) return true;
            }
        }
        return false;
    }

    public void reserve(AutomationRule rule) {
        for (RuleAction action : deviceActions(rule)) {
            reserved.computeIfAbsent(action.getDevice().getId(), ignored -> new ArrayList<>()).add(action);
        }
    }

    /** Device-level view of a rule: a scene action is replaced by the scene's own device actions. */
    private static List<RuleAction> deviceActions(AutomationRule rule) {
        List<RuleAction> result = new ArrayList<>();
        for (RuleAction action : rule.getActions()) {
            if (action.getScene() == null) {
                result.add(action);
                continue;
            }
            action.getScene().getActions().forEach(item -> result.add(RuleAction.builder()
                    .device(item.getTargetDevice()).action(item.getAction()).parameters(new HashMap<>()).build()));
        }
        return result;
    }

    private static boolean opposite(RuleAction left, RuleAction right) {
        String a = left.getAction();
        String b = right.getAction();
        boolean sameParameters = Objects.equals(left.getParameters(), right.getParameters());
        if ("SET_STATE".equals(a) || "SET_STATE".equals(b)) {
            return !("SET_STATE".equals(a) && "SET_STATE".equals(b) && sameParameters);
        }
        if (DeviceActionValues.POWER_ACTIONS.contains(a) && DeviceActionValues.POWER_ACTIONS.contains(b)) {
            if ("TOGGLE".equals(a) || "TOGGLE".equals(b)) return true;
            return DeviceActionValues.turnsOn(a, left.getParameters()) != DeviceActionValues.turnsOn(b, right.getParameters());
        }
        if (DeviceActionValues.GATE_ACTIONS.contains(a) && DeviceActionValues.GATE_ACTIONS.contains(b)) {
            return !DeviceActionValues.gateDirection(a).equals(DeviceActionValues.gateDirection(b));
        }
        return Objects.equals(a, b) && !sameParameters; // e.g. two SET_TEMPERATURE with different values
    }
}
