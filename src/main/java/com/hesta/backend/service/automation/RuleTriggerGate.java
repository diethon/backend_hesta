package com.hesta.backend.service.automation;

import com.hesta.backend.dto.request.AutomationEventRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops a matched rule from firing again and again.
 * <ul>
 *   <li>Edge trigger: devices report their state every few seconds, so a rule fires only when its
 *       conditions go from false to true, not on every report while they stay true.</li>
 *   <li>Cooldown: at most one run per rule per {@code cooldownSeconds}; damps flapping sensors and
 *       two rules switching each other back and forth.</li>
 * </ul>
 */
@Component
public class RuleTriggerGate {
    private final long cooldownSeconds;
    // shortcut: kept in memory per backend node; after a restart a still-true condition fires once. Move to Redis/DB for several nodes.
    private final Map<String, Boolean> lastMatch = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastFired = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public RuleTriggerGate(@Value("${hesta.automation.cooldown-seconds:10}") long cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    /**
     * Check and take the edge in one step, so two automation threads handling reports of the same rule
     * cannot both run it. Test events are answered but leave no trace.
     */
    public boolean tryClaim(UUID ruleId, AutomationEventRequest event, boolean matched, boolean bypassCooldown) {
        synchronized (locks.computeIfAbsent(ruleId, ignored -> new Object())) {
            if (!shouldFire(ruleId, event, matched, bypassCooldown)) return false;
            if (!event.isTest()) {
                handled(ruleId, event);
                fired(ruleId);
            }
            return true;
        }
    }

    /**
     * The edge is only consumed by {@link #handled}: a match held back by the cooldown fires on a later
     * report once the cooldown is over, instead of being lost until the condition turns false again.
     * @param bypassCooldown emergencies are never throttled
     */
    public boolean shouldFire(UUID ruleId, AutomationEventRequest event, boolean matched, boolean bypassCooldown) {
        if (event.isStateReport()) {
            String key = key(ruleId, event);
            if (!matched) {
                lastMatch.put(key, false);
                return false;
            }
            if (Boolean.TRUE.equals(lastMatch.get(key))) return false; // still true since it last ran
        }
        if (!matched) return false;
        return event.isTest() || bypassCooldown || !inCooldown(ruleId);
    }

    /** The rule ran (or was skipped for a conflict) on this event: wait for the next false→true edge. */
    public void handled(UUID ruleId, AutomationEventRequest event) {
        if (event.isStateReport()) lastMatch.put(key(ruleId, event), true);
    }

    public void fired(UUID ruleId) {
        lastFired.put(ruleId, Instant.now());
    }

    /** Called when a rule is edited, toggled or deleted. */
    public void forget(UUID ruleId) {
        String prefix = ruleId + ":";
        lastMatch.keySet().removeIf(key -> key.startsWith(prefix));
        lastFired.remove(ruleId);
    }

    private static String key(UUID ruleId, AutomationEventRequest event) {
        return ruleId + ":" + event.getSourceDeviceId();
    }

    private boolean inCooldown(UUID ruleId) {
        Instant last = lastFired.get(ruleId);
        return cooldownSeconds > 0 && last != null && last.isAfter(Instant.now().minusSeconds(cooldownSeconds));
    }
}
