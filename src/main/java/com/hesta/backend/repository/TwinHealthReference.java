package com.hesta.backend.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Scalar projection: no entity traversal or telemetry history loaded by health scans. */
public interface TwinHealthReference {
    UUID getHomeId();
    UUID getDeviceId();
    UUID getRoomId();
    String getMetricType();
    OffsetDateTime getReferenceTime();
}
