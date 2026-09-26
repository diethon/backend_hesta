package com.hesta.backend.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.TwinHealthProperties;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.service.TwinHealthStatusResolver;
import com.hesta.backend.service.impl.TwinHealthStatusResolverImpl;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

public final class TwinHealthTestSupport {
    public static final Clock FIXED_CLOCK = Clock.fixed(TwinFixtures.TIME.toInstant(), ZoneOffset.UTC);

    public static TwinHealthProperties properties() {
        return new TwinHealthProperties(Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofHours(1), false);
    }

    public static TwinHealthStatusResolver resolver(Clock clock) {
        return new TwinHealthStatusResolverImpl(properties(), clock);
    }

    public static TwinSnapshotMapper mapper(ObjectMapper objectMapper) {
        return new TwinSnapshotMapper(objectMapper, resolver(FIXED_CLOCK), FIXED_CLOCK);
    }
}
