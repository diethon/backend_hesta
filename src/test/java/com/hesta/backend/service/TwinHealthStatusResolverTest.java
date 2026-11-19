package com.hesta.backend.service;

import com.hesta.backend.enums.TwinHealthStatus;
import com.hesta.backend.support.MutableClock;
import com.hesta.backend.support.TwinFixtures;
import com.hesta.backend.support.TwinHealthTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TwinHealthStatusResolverTest {
    private final MutableClock clock = new MutableClock(TwinFixtures.TIME.toInstant());
    private final TwinHealthStatusResolver resolver = TwinHealthTestSupport.resolver(clock);

    @ParameterizedTest
    @CsvSource({"0,ACTIVE", "29999,ACTIVE", "30000,STALE", "60000,STALE",
            "299999,STALE", "300000,OFFLINE", "600000,OFFLINE", "-1000,ACTIVE"})
    void resolve_exactAge_usesOneBoundaryRule(long ageMillis, TwinHealthStatus expected) {
        clock.advance(Duration.ofMillis(ageMillis));
        assertThat(resolver.resolve(TwinFixtures.TIME)).isEqualTo(expected);
        assertThat(resolver.resolve(TwinFixtures.TIME)).isEqualTo(expected);
    }

    @Test
    void resolve_missingTimestamp_isOfflineWithoutInventingLastSeen() {
        assertThat(resolver.resolve(null)).isEqualTo(TwinHealthStatus.OFFLINE);
    }

    @Test
    void resolve_oneNanosecondBeforeStale_remainsActive() {
        clock.advance(Duration.ofSeconds(30).minusNanos(1));
        assertThat(resolver.resolve(TwinFixtures.TIME)).isEqualTo(TwinHealthStatus.ACTIVE);
        clock.advance(Duration.ofNanos(1));
        assertThat(resolver.resolve(TwinFixtures.TIME)).isEqualTo(TwinHealthStatus.STALE);
    }
}
