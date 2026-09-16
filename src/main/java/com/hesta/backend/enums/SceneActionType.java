package com.hesta.backend.enums;

import java.util.Locale;
import java.util.Optional;

public enum SceneActionType {
    TURN_ON,
    TURN_OFF,
    SET_BRIGHTNESS,
    SET_TEMPERATURE,
    SET_SPEED,
    SET_STATE;

    public static Optional<SceneActionType> from(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
