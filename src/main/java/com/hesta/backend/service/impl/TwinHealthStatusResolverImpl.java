package com.hesta.backend.service.impl;

import com.hesta.backend.config.TwinHealthProperties;
import com.hesta.backend.enums.TwinHealthStatus;
import com.hesta.backend.service.TwinHealthStatusResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TwinHealthStatusResolverImpl implements TwinHealthStatusResolver {
    private final TwinHealthProperties properties;
    private final Clock clock;

    @Override
    public TwinHealthStatus resolve(OffsetDateTime referenceTime) {
        return resolve(referenceTime, clock.instant());
    }

    @Override
    public TwinHealthStatus resolve(OffsetDateTime referenceTime, Instant evaluatedAt) {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt is required");
        if (referenceTime == null) return TwinHealthStatus.OFFLINE;
        Duration age = Duration.between(referenceTime.toInstant(), evaluatedAt);
        if (age.compareTo(properties.staleAfter()) < 0) return TwinHealthStatus.ACTIVE;
        if (age.compareTo(properties.offlineAfter()) < 0) return TwinHealthStatus.STALE;
        return TwinHealthStatus.OFFLINE;
    }
}
