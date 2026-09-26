package com.hesta.backend.service;

import com.hesta.backend.enums.TwinHealthStatus;
import java.time.Instant;
import java.time.OffsetDateTime;

public interface TwinHealthStatusResolver {
    TwinHealthStatus resolve(OffsetDateTime referenceTime);
    TwinHealthStatus resolve(OffsetDateTime referenceTime, Instant evaluatedAt);
}
