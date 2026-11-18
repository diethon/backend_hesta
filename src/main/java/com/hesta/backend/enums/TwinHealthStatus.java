package com.hesta.backend.enums;

/** Sensor freshness; device compatibility health is derived only from DeviceStatus. */
public enum TwinHealthStatus {
    ACTIVE, STALE, OFFLINE;

    public static TwinHealthStatus forDevice(DeviceStatus status) {
        return status == DeviceStatus.ONLINE ? ACTIVE : OFFLINE;
    }
}
