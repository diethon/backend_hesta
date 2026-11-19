package com.hesta.backend.enums;

/**
 * Access level for a room or device in CUSTOM member scope.
 * - NONE: Hidden resource. Cannot view state, history, or execute commands (returns 404).
 * - VIEW: Read-only access. Can view state, metrics, and history, but cannot execute commands (returns 403 on command).
 * - CONTROL: Full member control. Can view state, history, and execute commands / actions.
 */
public enum AccessLevel {
    NONE,
    VIEW,
    CONTROL;

    public boolean canView() {
        return this == VIEW || this == CONTROL;
    }

    public boolean canControl() {
        return this == CONTROL;
    }
}

