package com.hesta.backend.service.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single place that knows what value each device action takes.
 * Scenes store a raw JSON value, automation rules store a parameter map; both are validated here
 * and both are turned into device command parameters here.
 */
public final class DeviceActionValues {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Set<String> POWER_ACTIONS = Set.of("TURN_ON", "TURN_OFF", "TOGGLE", "POWER_ON", "POWER_OFF", "SET_POWER");
    public static final Set<String> GATE_ACTIONS = Set.of("OPEN", "CLOSE", "STOP", "GATE_OPEN", "GATE_CLOSE", "GATE_STOP");
    /** Actions whose value is not shown back to the user (they carry no setting). */
    private static final Set<String> NO_VALUE_ACTIONS = Set.of("TURN_ON", "TURN_OFF", "TOGGLE", "CANCEL_TIMER",
            "OPEN", "CLOSE", "STOP", "GATE_OPEN", "GATE_CLOSE", "GATE_STOP");

    /** One command sent to a device. */
    public record DeviceCommand(String action, Map<String, Object> parameters) {}

    private DeviceActionValues() {}

    public static String normalize(String action) {
        return action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean takesNoValue(String action) {
        return NO_VALUE_ACTIONS.contains(normalize(action));
    }

    // ---------------------------------------------------------------- validation

    /** Validates a scene value or a rule's parameters (pass {@code MAPPER.valueToTree(parameters)}). */
    public static boolean isValidValue(String action, JsonNode value) {
        boolean empty = value == null || value.isNull() || (value.isObject() && value.isEmpty())
                || (value.isTextual() && value.asText().isBlank());
        return switch (normalize(action)) {
            // Power and gate commands may carry extra settings (e.g. AC temperature learned by recommendations).
            case "TURN_ON", "TURN_OFF", "TOGGLE", "CANCEL_TIMER",
                 "OPEN", "CLOSE", "STOP", "GATE_OPEN", "GATE_CLOSE", "GATE_STOP" -> empty || value.isObject();
            case "SET_POWER" -> empty || value.isBoolean() || value.isNumber() || value.isTextual() || value.has("power");
            case "SET_BRIGHTNESS" -> isPercent(field(value, "brightness", "level"));
            case "SET_SPEED" -> {
                JsonNode speed = field(value, "speed", "fan", "level");
                yield isPercent(speed) || (value.isObject() && speed != null && speed.isTextual());
            }
            case "SET_FAN" -> {
                JsonNode fan = field(value, "fan", "speed");
                yield isPercent(fan) || (fan != null && fan.isTextual() && !fan.asText().isBlank());
            }
            case "SET_TEMPERATURE" -> {
                JsonNode temperature = field(value, "temperature", "temp");
                yield temperature != null && temperature.isNumber();
            }
            case "TEMPERATURE_PLUS", "TEMPERATURE_MINUS", "TEMP_UP", "TEMP_DOWN" -> empty || value.isNumber() || value.isObject();
            case "SET_MODE" -> {
                JsonNode mode = field(value, "mode");
                yield mode != null && mode.isTextual() && !mode.asText().isBlank();
            }
            case "SET_SWING" -> empty || value.isBoolean() || value.isTextual() || value.isObject();
            case "SET_TIMER" -> {
                JsonNode hour = field(value, "hour");
                yield hour != null && hour.isIntegralNumber() && hour.intValue() >= 0 && hour.intValue() <= 24;
            }
            case "SET_COLOR", "SET_RGB" -> value != null && (value.isTextual()
                    ? value.asText().startsWith("#") || value.asText().startsWith("{")
                    : value.isObject() && (value.has("hex") || value.has("color") || (value.has("r") && value.has("g") && value.has("b"))));
            case "SET_STATE" -> value != null && value.isObject() && !value.isEmpty();
            default -> true; // device.supportsAction() already decided the action is known
        };
    }

    public static boolean isValidParameters(String action, Map<String, Object> parameters) {
        return isValidValue(action, MAPPER.valueToTree(parameters == null ? Map.of() : parameters));
    }

    // ---------------------------------------------------------------- scene value -> command parameters

    /** Turns a scene's stored JSON value into the parameter map the device command expects. */
    public static Map<String, Object> toParameters(String action, JsonNode value) {
        if (value == null || value.isNull()) return Map.of();
        return switch (normalize(action)) {
            case "SET_BRIGHTNESS" -> sameValue(number(value, 50, "brightness", "level"), "brightness", "level");
            case "SET_SPEED" -> sameValue(number(value, 50, "speed", "fan"), "speed", "fan");
            case "SET_TEMPERATURE" -> sameValue(number(value, 26, "temperature", "temp"), "temperature", "temp");
            case "TEMPERATURE_PLUS", "TEMP_UP", "TEMPERATURE_MINUS", "TEMP_DOWN" -> {
                if (value.isObject() && value.has("amount")) yield Map.of("amount", value.get("amount").asInt(1));
                yield value.isNumber() ? Map.of("amount", value.asInt()) : Map.of();
            }
            case "SET_COLOR", "SET_RGB" -> color(value);
            case "SET_MODE" -> Map.of("mode", text(value, "mode").toUpperCase(Locale.ROOT));
            case "SET_FAN" -> sameValue(text(value, "fan", "speed").toUpperCase(Locale.ROOT), "fan", "speed");
            case "SET_SWING" -> {
                JsonNode swing = field(value, "swing", "enabled");
                boolean on = swing == null || (swing.isTextual()
                        ? "true".equalsIgnoreCase(swing.asText()) || "on".equalsIgnoreCase(swing.asText())
                        : swing.asBoolean(true));
                yield sameValue(on, "swing", "enabled");
            }
            case "SET_TIMER" -> {
                Map<String, Object> timer = new HashMap<>();
                if (value.isObject()) {
                    if (value.has("hour")) timer.put("hour", value.get("hour").asInt(1));
                    if (value.has("halfHour")) timer.put("halfHour", value.get("halfHour").asBoolean(false));
                } else if (value.isIntegralNumber()) {
                    timer.put("hour", value.asInt());
                    timer.put("halfHour", false);
                }
                yield timer;
            }
            case "SET_POWER" -> {
                JsonNode power = value.isObject() ? value.get("power") : value;
                yield Map.of("power", power == null || isOn(power)); // no explicit power means "on"
            }
            case "CANCEL_TIMER" -> Map.of();
            default -> value.isObject() ? MAPPER.convertValue(value, new TypeReference<Map<String, Object>>() {}) : Map.of();
        };
    }

    /**
     * SET_STATE bundles several settings ({power, brightness, color, temperature, mode, ...});
     * devices only understand one setting per command, so split it in the order a person would do it.
     */
    public static List<DeviceCommand> expandSetState(Map<String, Object> params) {
        List<DeviceCommand> commands = new ArrayList<>();
        if (params == null || params.isEmpty()) return List.of(new DeviceCommand("SET_STATE", Map.of()));

        // SET_COLOR already carries power ON + brightness, so no separate TURN_ON / SET_BRIGHTNESS round trips.
        boolean hasColor = params.containsKey("r") || params.containsKey("color") || params.containsKey("hex");
        if (params.containsKey("power")) {
            if (!isOn(params.get("power"))) return List.of(new DeviceCommand("TURN_OFF", Map.of())); // off ends the list
            if (!hasColor) commands.add(new DeviceCommand("TURN_ON", Map.of()));
        }
        Object brightness = first(params, "brightness", "level");
        if (brightness != null && !hasColor) {
            commands.add(new DeviceCommand("SET_BRIGHTNESS", mutable(sameValue(brightness, "brightness", "level"))));
        }
        if (hasColor) {
            Map<String, Object> color = new HashMap<>();
            color.put("power", "ON");
            color.put("brightness", brightness != null ? brightness : 100);
            if (params.containsKey("r") && params.containsKey("g") && params.containsKey("b")) {
                putRgb(color, toInt(params.get("r")), toInt(params.get("g")), toInt(params.get("b")));
            } else {
                putHex(color, String.valueOf(first(params, "hex", "color")));
            }
            commands.add(new DeviceCommand("SET_COLOR", color));
        }
        Object temperature = first(params, "temperature", "temp");
        if (temperature != null) commands.add(new DeviceCommand("SET_TEMPERATURE", sameValue(temperature, "temperature", "temp")));
        if (params.get("mode") != null) {
            commands.add(new DeviceCommand("SET_MODE", Map.of("mode", params.get("mode").toString().toUpperCase(Locale.ROOT))));
        }
        Object fan = first(params, "fan", "speed");
        if (fan != null) commands.add(new DeviceCommand("SET_SPEED", sameValue(fan, "speed", "fan")));
        if (params.containsKey("swing")) commands.add(new DeviceCommand("SET_SWING", sameValue(isOn(params.get("swing")), "swing", "enabled")));
        Object hour = first(params, "hour", "timer");
        if (hour != null) {
            Map<String, Object> timer = new HashMap<>();
            timer.put("hour", hour);
            if (params.containsKey("halfHour")) timer.put("halfHour", params.get("halfHour"));
            commands.add(new DeviceCommand("SET_TIMER", timer));
        } else if (Boolean.TRUE.equals(params.get("cancelTimer"))) {
            commands.add(new DeviceCommand("CANCEL_TIMER", Map.of()));
        }
        Object gate = first(params, "gateAction", "gate", "state");
        if (gate != null && GATE_ACTIONS.contains(normalize(gate.toString()))) {
            Map<String, Object> gateParams = new HashMap<>();
            if (params.containsKey("nodeId")) gateParams.put("nodeId", params.get("nodeId"));
            commands.add(new DeviceCommand(normalize(gate.toString()), gateParams));
        }
        return commands.isEmpty() ? List.of(new DeviceCommand("SET_STATE", params)) : commands;
    }

    // ---------------------------------------------------------------- power / gate meaning (used by conflict checks)

    /** Whether a power-type action ends with the device on. */
    public static boolean turnsOn(String action, Map<String, Object> params) {
        String normalized = normalize(action);
        if ("TURN_OFF".equals(normalized) || "POWER_OFF".equals(normalized)) return false;
        if ("SET_POWER".equals(normalized) && params != null && params.containsKey("power")) return isOn(params.get("power"));
        return true;
    }

    /**
     * The action as a habit: TOGGLE / POWER_ON / SET_POWER become TURN_ON or TURN_OFF (TOGGLE flips the
     * power the device had before), GATE_* become OPEN / CLOSE / STOP; anything else stays as sent.
     */
    public static String habitAction(String action, Map<String, Object> params, Map<String, Object> previousState) {
        String normalized = normalize(action);
        if ("TOGGLE".equals(normalized)) {
            Object power = previousState == null ? null : previousState.get("power");
            return power != null && isOn(power) ? "TURN_OFF" : "TURN_ON";
        }
        if ("SET_STATE".equals(normalized) && params != null && params.containsKey("power")) {
            return isOn(params.get("power")) ? "TURN_ON" : "TURN_OFF";
        }
        if (POWER_ACTIONS.contains(normalized)) return turnsOn(normalized, params) ? "TURN_ON" : "TURN_OFF";
        if (GATE_ACTIONS.contains(normalized)) return gateDirection(normalized);
        return normalized;
    }

    /** OPEN / CLOSE / STOP regardless of the GATE_ prefix. */
    public static String gateDirection(String action) {
        String normalized = normalize(action);
        return normalized.startsWith("GATE_") ? normalized.substring(5) : normalized;
    }

    // ---------------------------------------------------------------- helpers

    /** Truthy values coming from JSON, forms or hardware: true, 1, "on", "true". */
    public static boolean isOn(Object value) {
        if (value instanceof JsonNode node) {
            if (node.isBoolean()) return node.booleanValue();
            if (node.isNumber()) return node.intValue() != 0;
            value = node.asText();
        }
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        String text = String.valueOf(value).trim();
        return "ON".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    /** For an object, the first present key; for a plain value, the value itself. */
    private static JsonNode field(JsonNode value, String... keys) {
        if (value == null || !value.isObject()) return value;
        for (String key : keys) if (value.has(key)) return value.get(key);
        return null;
    }

    private static boolean isPercent(JsonNode node) {
        return node != null && node.isNumber() && node.doubleValue() >= 0 && node.doubleValue() <= 100;
    }

    private static Number number(JsonNode value, Number fallback, String... keys) {
        JsonNode node = field(value, keys);
        return node != null && node.isNumber() ? node.numberValue() : fallback;
    }

    private static String text(JsonNode value, String... keys) {
        JsonNode node = field(value, keys);
        return node == null ? "" : node.asText();
    }

    /** Devices read different key names for the same setting, so send the value under each of them. */
    private static Map<String, Object> sameValue(Object value, String... keys) {
        Map<String, Object> map = new HashMap<>();
        for (String key : keys) map.put(key, value);
        return map;
    }

    private static Map<String, Object> mutable(Map<String, Object> map) {
        return new HashMap<>(map);
    }

    private static Object first(Map<String, Object> params, String... keys) {
        for (String key : keys) if (params.get(key) != null) return params.get(key);
        return null;
    }

    private static Map<String, Object> color(JsonNode value) {
        Map<String, Object> color = new HashMap<>();
        color.put("power", "ON");
        color.put("brightness", 100);
        if (value.isTextual()) {
            String text = value.asText().trim();
            if (text.startsWith("{")) {
                try {
                    return color(MAPPER.readTree(text));
                } catch (Exception ignored) {
                    return color;
                }
            }
            putHex(color, text);
            return color;
        }
        JsonNode brightness = field(value, "brightness", "level");
        if (brightness != null && brightness.isNumber()) color.put("brightness", brightness.numberValue());
        if (value.has("r") && value.has("g") && value.has("b")) {
            putRgb(color, value.get("r").asInt(), value.get("g").asInt(), value.get("b").asInt());
        } else if (value.has("hex") || value.has("color")) {
            putHex(color, text(value, "hex", "color"));
        }
        return color;
    }

    private static void putRgb(Map<String, Object> target, Integer r, Integer g, Integer b) {
        if (r == null || g == null || b == null) return;
        String hex = String.format("#%02x%02x%02x", r, g, b);
        target.put("r", r);
        target.put("g", g);
        target.put("b", b);
        target.put("hex", hex);
        target.put("color", hex);
    }

    private static void putHex(Map<String, Object> target, String hex) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) return;
        putRgb(target, Integer.parseInt(hex.substring(1, 3), 16), Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16));
        target.put("hex", hex);
        target.put("color", hex);
    }

    private static Integer toInt(Object value) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
