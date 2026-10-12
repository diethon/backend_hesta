package com.hesta.backend.diagnostics;

public enum CircuitState {
    CLOSED,     // Healthy, normal operation
    OPEN,       // Tripped/Quarantined due to repeated packet drops or hardware failure
    HALF_OPEN   // Testing recovery with limited trial probe
}
