package com.hesta.backend.service.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.entity.AutomationRule;
import com.hesta.backend.entity.RuleCondition;
import com.hesta.backend.enums.LogicalOperator;
import com.hesta.backend.enums.TriggerType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Decides whether an event satisfies a rule's conditions. Pure logic: no database, no side effects. */
@Component
public class RuleMatcher {
    /** Condition attribute compared against the current local time as "HH:mm" (e.g. timeOfDay GTE "18:00"). */
    public static final String TIME_OF_DAY = "timeOfDay";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> TRUE_WORDS = Set.of("true", "1", "yes", "on", "detected", "active");
    private static final Set<String> FALSE_WORDS = Set.of("false", "0", "no", "off", "none", "inactive");

    private final Clock clock;

    public RuleMatcher(@Value("${hesta.scheduler.zone:Asia/Ho_Chi_Minh}") String zone) {
        this.clock = Clock.system(ZoneId.of(zone));
    }

    public boolean matches(AutomationRule rule, AutomationEventRequest event) {
        // SCHEDULE rules run from the scheduler, never from events.
        if (rule.getTriggerType() == TriggerType.SCHEDULE) return false;
        List<RuleCondition> conditions = rule.getConditions().stream()
                .sorted(Comparator.comparingInt(RuleCondition::getOrder)).toList();
        if (conditions.isEmpty() || !isTriggeredBy(conditions, event)) return false;

        // AND binds tighter than OR: a AND b OR c AND d  ==  (a AND b) OR (c AND d)
        boolean anyGroupTrue = false;
        boolean group = holds(conditions.getFirst(), event);
        for (RuleCondition condition : conditions.subList(1, conditions.size())) {
            boolean current = holds(condition, event);
            if (condition.getLogicalOperator() == LogicalOperator.OR) {
                anyGroupTrue |= group;
                group = current;
            } else {
                group &= current;
            }
        }
        return anyGroupTrue || group;
    }

    /**
     * Only the device a rule watches can trigger it (or any device, for a condition without a device).
     * Conditions on other devices and on the time of day are read as current state, not triggers.
     */
    private boolean isTriggeredBy(List<RuleCondition> conditions, AutomationEventRequest event) {
        return conditions.stream().anyMatch(condition -> condition.getDevice() == null
                ? !TIME_OF_DAY.equals(condition.getAttribute())
                : condition.getDevice().getId().equals(event.getSourceDeviceId()));
    }

    private boolean holds(RuleCondition condition, AutomationEventRequest event) {
        Object actual = actualValue(condition, event);
        if (actual == null) return false;
        int comparison = compare(plain(actual), plain(condition.getExpectedValue()));
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

    private Object actualValue(RuleCondition condition, AutomationEventRequest event) {
        String attribute = condition.getAttribute();
        if (TIME_OF_DAY.equals(attribute)) return LocalTime.now(clock).format(HH_MM);
        boolean otherDevice = condition.getDevice() != null && !condition.getDevice().getId().equals(event.getSourceDeviceId());
        if (otherDevice) {
            // e.g. "door opened AND it is dark": the light sensor's last known value.
            Map<String, Object> state = condition.getDevice().getCurrentState();
            return state == null ? null : state.get(attribute);
        }
        return "eventType".equals(attribute) ? event.getEventType() : event.getData().get(attribute);
    }

    /** Numbers compare as numbers, on/off-like words as booleans, everything else as case-insensitive text. */
    private static int compare(Object actual, Object expected) {
        if (actual instanceof Number left && expected instanceof Number right) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
        }
        Boolean left = asBoolean(actual);
        Boolean right = asBoolean(expected);
        if (left != null && right != null) return left.compareTo(right);
        return String.valueOf(actual).compareToIgnoreCase(String.valueOf(expected));
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.doubleValue() != 0.0;
        if (value instanceof String text) {
            String lower = text.toLowerCase();
            if (TRUE_WORDS.contains(lower)) return true;
            if (FALSE_WORDS.contains(lower)) return false;
        }
        return null;
    }

    private static Object plain(Object value) {
        return value instanceof JsonNode node ? MAPPER.convertValue(node, Object.class) : value;
    }
}
