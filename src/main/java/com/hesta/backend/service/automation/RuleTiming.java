package com.hesta.backend.service.automation;

import com.hesta.backend.service.execution.DeviceActionValues;

import java.util.HashMap;
import java.util.Map;

/** The timing parts of a rule: DELAY (wait) steps, "auto off after X seconds" and the hold time. */
public final class RuleTiming {
    /** A step with no target that waits {@code parameters.seconds} before the next steps run. */
    public static final String DELAY = "DELAY";
    public static final String SECONDS = "seconds";
    /** Optional on a turn-on/open step: send the opposite command after this many seconds. */
    public static final String AUTO_OFF = "autoOffSeconds";
    public static final int MAX_DELAY_SECONDS = 3600;
    public static final int MAX_AUTO_OFF_SECONDS = 24 * 3600;
    public static final int MAX_HOLD_SECONDS = 3600;

    private RuleTiming() {
    }

    /** Seconds of a DELAY step, or -1 when the parameters are not a single whole number in range. */
    public static int delaySeconds(Map<String, Object> parameters) {
        if (parameters == null || parameters.size() != 1) return -1;
        return wholeSeconds(parameters.get(SECONDS), MAX_DELAY_SECONDS);
    }

    /** 0 when the step has no auto-off, -1 when the value is invalid. */
    public static int autoOffSeconds(Map<String, Object> parameters) {
        if (parameters == null || !parameters.containsKey(AUTO_OFF)) return 0;
        return wholeSeconds(parameters.get(AUTO_OFF), MAX_AUTO_OFF_SECONDS);
    }

    /** The parameters sent to the device: everything except the auto-off setting. */
    public static Map<String, Object> commandParameters(Map<String, Object> parameters) {
        Map<String, Object> result = parameters == null ? new HashMap<>() : new HashMap<>(parameters);
        result.remove(AUTO_OFF);
        return result;
    }

    /** The command that undoes a turn-on/open step, or null when the step cannot be auto-undone. */
    public static String undoAction(String action, Map<String, Object> parameters) {
        if (action == null) return null;
        String normalized = action.trim().toUpperCase(java.util.Locale.ROOT);
        if (DeviceActionValues.POWER_ACTIONS.contains(normalized) && !"TOGGLE".equals(normalized)) {
            return DeviceActionValues.turnsOn(normalized, commandParameters(parameters)) ? "TURN_OFF" : null;
        }
        if (DeviceActionValues.GATE_ACTIONS.contains(normalized)) {
            return "OPEN".equals(DeviceActionValues.gateDirection(normalized)) ? "CLOSE" : null;
        }
        return null;
    }

    private static int wholeSeconds(Object value, int max) {
        if (!(value instanceof Number number)) return -1;
        double seconds = number.doubleValue();
        if (seconds != Math.rint(seconds) || seconds < 1 || seconds > max) return -1;
        return (int) seconds;
    }
}
