package com.hesta.backend.realtime.model;

public enum RealtimeEventType {
    SENSOR_READING_UPDATED,
    DEVICE_STATE_CHANGED,
    TWIN_HEALTH_STATUS_CHANGED,
    NOTIFICATION_CREATED,
    /** A scene, its actions, schedule or run history changed; clients refetch their scene list. */
    SCENE_CHANGED,
    /** An automation rule, its schedule or run history changed; clients refetch their rule list. */
    AUTOMATION_RULE_CHANGED,
    /** One scene run progressed or finished; data is the SceneExecutionResponse (PENDING while devices still answer). */
    SCENE_EXECUTION_UPDATED
}
