package com.hesta.backend.falldetection.enums;

/**
 * 7 Trạng thái FSM của sự cố té ngã (Master Plan v3.0).
 */
public enum FallIncidentState {
    DETECTED,
    SUSPECTED_COUNTDOWN,
    ACKNOWLEDGED,
    ESCALATED_SOS,
    RESOLVED,
    FALSE_ALARM,
    POST_FALL_MONITOR
}
