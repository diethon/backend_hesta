package com.hesta.backend.service.automation;

import com.hesta.backend.entity.AutomationRecommendation;
import com.hesta.backend.entity.AutomationRule;
import com.hesta.backend.entity.RuleAction;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.repository.AutomationRecommendationRepository;
import com.hesta.backend.repository.AutomationRuleRepository;
import com.hesta.backend.repository.SceneActionRepository;
import com.hesta.backend.repository.SceneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

/**
 * Removes a device from the scenes, rules and recommendations that use it, before the device is deleted.
 * Deleted devices are hidden by {@code @SQLRestriction}, so a leftover reference would load as null:
 * a rule condition would then match any device and a rule action would fail at run time.
 * Execution history is kept (it stores device ids as JSON, not as foreign keys).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceReferenceCleaner {
    private final SceneActionRepository sceneActions;
    private final SceneRepository scenes;
    private final AutomationRuleRepository rules;
    private final AutomationRecommendationRepository recommendations;
    private final RuleTriggerGate triggerGate;

    /** Call in the same transaction, before the device is marked deleted. */
    public void detach(UUID deviceId) {
        Set<Scene> affectedScenes = new LinkedHashSet<>();
        sceneActions.findAllByTargetDeviceId(deviceId).forEach(action -> affectedScenes.add(action.getScene()));
        for (Scene scene : affectedScenes) {
            scene.getActions().removeIf(action -> deviceId.equals(action.getTargetDevice().getId()));
            scenes.saveAndFlush(scene); // delete first, so renumbering never collides with a removed row
            renumber(scene.getActions(), SceneAction::getOrder, SceneAction::setOrder, scenes::flush);
        }

        for (AutomationRule rule : rules.findAllReferencingDevice(deviceId)) {
            rule.getActions().removeIf(action -> action.getDevice() != null && deviceId.equals(action.getDevice().getId()));
            rules.saveAndFlush(rule);
            renumber(rule.getActions(), RuleAction::getOrder, RuleAction::setOrder, rules::flush);
            boolean watchesDevice = rule.getConditions().stream()
                    .anyMatch(condition -> condition.getDevice() != null && deviceId.equals(condition.getDevice().getId()));
            // Dropping a condition would silently change what the rule means, so the user has to fix it.
            if (rule.isEnabled() && (watchesDevice || rule.getActions().isEmpty())) {
                rule.setEnabled(false);
                log.info("Disabled rule {} because device {} was removed", rule.getId(), deviceId);
            }
            triggerGate.forget(rule.getId());
        }

        OffsetDateTime now = OffsetDateTime.now();
        for (AutomationRecommendation recommendation : recommendations.findAllByDeviceIdAndStatus(deviceId, "PENDING")) {
            // Not the user's answer, so no feedback is sent to the ML training data.
            recommendation.setStatus("EXPIRED");
            recommendation.setResolvedAt(now);
        }
    }

    /**
     * Orders become 0..n-1 again. Goes up one row at a time: each row only moves down into a slot that is
     * already free, so the (parent, order) unique constraint holds even though it is checked per statement.
     */
    private static <T> void renumber(List<T> items, ToIntFunction<T> order, ObjIntConsumer<T> setOrder, Runnable flush) {
        List<T> sorted = items.stream().sorted(Comparator.comparingInt(order)).toList();
        for (int index = 0; index < sorted.size(); index++) {
            if (order.applyAsInt(sorted.get(index)) != index) {
                setOrder.accept(sorted.get(index), index);
                flush.run();
            }
        }
    }
}
