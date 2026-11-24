package com.hesta.backend.energy;

import com.hesta.backend.entity.Device;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Shared timezone, baseline wattage, and active-power evaluation rules for Energy Analytics & Demand Response.
 */
public final class EnergyPowerCalculator {

    public static final ZoneId ENERGY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final List<String> POWER_METRIC_KEYS = List.of(
            "POWER", "WATT", "WATTS", "ACTIVE_POWER", "power_w", "watts", "wattage"
    );

    private EnergyPowerCalculator() {
    }

    public static OffsetDateTime nowVn() {
        return ZonedDateTime.now(ENERGY_ZONE).toOffsetDateTime();
    }

    public static OffsetDateTime toVn(OffsetDateTime dt) {
        return dt == null ? null : dt.atZoneSameInstant(ENERGY_ZONE).toOffsetDateTime();
    }

    public static int resolveBaseWattage(Device device) {
        if (device == null) return 60;

        if (device.getSettings() != null && device.getSettings().get("wattage") != null) {
            try {
                int customW = (int) Math.round(Double.parseDouble(device.getSettings().get("wattage").toString().trim()));
                if (customW > 0) return customW;
            } catch (NumberFormatException ignored) {}
        }

        if (device.getDeviceType() == null) return 60;
        String type = device.getDeviceType().trim().toUpperCase();

        return switch (type) {
            case "AIR_CONDITIONER", "AC", "AC_REMOTE" -> 1500;
            case "HEATER", "WATER_HEATER" -> 2500;
            case "SMART_PLUG", "SOCKET" -> 500;
            case "WATER_PUMP" -> 370;
            case "GATE", "GATE_MOTOR", "ROLLING_DOOR", "DOOR" -> 350;
            case "FRIDGE" -> 150;
            case "TV" -> 120;
            case "FAN" -> 65;
            case "CURTAIN" -> 60;
            case "LIGHT", "LED" -> 40;
            case "LED_RGB" -> 24;
            case "CAMERA_AI", "CAMERA" -> 15;
            case "ROUTER" -> 12;
            case "IR_REMOTE" -> 3;
            case "TEMP_HUMID_SENSOR", "LUX_SENSOR", "FLAME_SENSOR", "SMOKE_SENSOR",
                    "MOTION_SENSOR", "WATER_LEAK_SENSOR", "DOOR_SENSOR", "SENSOR" -> 2;
            default -> {
                if (type.contains("SENSOR") || type.contains("SMOKE") || type.contains("GAS")
                        || type.contains("LEAK") || type.contains("FLAME")) {
                    yield 2;
                }
                yield 60;
            }
        };
    }

    public static boolean isStateOn(Device device, Map<String, Object> state) {
        if (state == null || state.isEmpty()) return false;

        Object power = state.get("power");
        if (power != null) {
            String pStr = power.toString().trim();
            if ("ON".equalsIgnoreCase(pStr) || "TRUE".equalsIgnoreCase(pStr) || "1".equals(pStr)) {
                return true;
            }
        }

        Object st = state.get("state");
        if (st != null) {
            String sStr = st.toString().trim().toUpperCase();
            String type = device != null && device.getDeviceType() != null
                    ? device.getDeviceType().trim().toUpperCase()
                    : "";
            if (isActuatorAperture(type)) {
                // Gates/doors only draw motor power while moving, never when resting OPEN.
                return "OPENING".equals(sStr) || "CLOSING".equals(sStr) || "RUNNING".equals(sStr);
            }
            return "OPENING".equals(sStr) || "OPEN".equals(sStr) || "RUNNING".equals(sStr);
        }
        return false;
    }

    private static boolean isActuatorAperture(String upperType) {
        return "GATE".equals(upperType)
                || "GATE_MOTOR".equals(upperType)
                || "ROLLING_DOOR".equals(upperType)
                || "DOOR".equals(upperType)
                || "CURTAIN".equals(upperType);
    }

    public static double resolveEffectiveWattage(Device device, int baseWattage, Map<String, Object> state) {
        if (state == null || state.isEmpty()) return baseWattage;

        for (String key : POWER_METRIC_KEYS) {
            Object raw = state.get(key);
            if (raw != null) {
                try {
                    double measured = Double.parseDouble(raw.toString().trim());
                    if (measured > 0) {
                        return Math.round(measured * 10.0) / 10.0;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        String type = device != null && device.getDeviceType() != null
                ? device.getDeviceType().trim().toUpperCase()
                : "";

        if ((type.contains("LIGHT") || type.contains("LED")) && state.get("brightness") != null) {
            try {
                double brightness = Double.parseDouble(state.get("brightness").toString().trim());
                if (brightness > 0) {
                    double factor = Math.min(Math.max(brightness, 1.0), 100.0) / 100.0;
                    return Math.round(baseWattage * factor * 10.0) / 10.0;
                }
            } catch (NumberFormatException ignored) {}
        }

        if (type.contains("FAN") && state.get("speed") != null) {
            try {
                int speed = Integer.parseInt(state.get("speed").toString().trim());
                if (speed == 1) return baseWattage * 0.5;
                if (speed == 2) return baseWattage * 0.75;
                if (speed >= 3) return baseWattage * 1.0;
            } catch (NumberFormatException ignored) {}
        }

        if ("AC".equals(type) || type.contains("AIR_CONDITIONER")) {
            Object modeObj = state.get("mode");
            if (modeObj != null) {
                String mode = modeObj.toString().trim().toUpperCase();
                if ("FAN_ONLY".equals(mode) || "FAN".equals(mode)) {
                    return Math.round(baseWattage * 0.10 * 10.0) / 10.0;
                }
                if ("DRY".equals(mode)) {
                    return Math.round(baseWattage * 0.60 * 10.0) / 10.0;
                }
            }
            Object tempObj = state.get("temperature");
            if (tempObj != null) {
                try {
                    double temp = Double.parseDouble(tempObj.toString().trim());
                    if (temp >= 16.0 && temp <= 32.0) {
                        double factor = Math.max(0.65, Math.min(1.20, 1.0 + (24.0 - temp) * 0.04));
                        return Math.round(baseWattage * factor * 10.0) / 10.0;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        return baseWattage;
    }
}
