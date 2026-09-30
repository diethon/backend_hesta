package com.hesta.backend.enums;

import java.util.Locale;
import java.util.Optional;

public enum ConditionOperator {
    EQ, NE, GT, GTE, LT, LTE;

    public static Optional<ConditionOperator> from(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
