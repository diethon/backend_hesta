package com.hesta.backend.service;

import com.hesta.backend.config.MaintenanceRateLimitProperties;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaintenanceRateLimiterTest {

    @Test
    void enforcesDailyMessageUploadAndActiveConversationLimitsWithHttp429() {
        MaintenanceRateLimitProperties props = new MaintenanceRateLimitProperties();
        props.setMessagesPerDay(2);
        props.setUploadsPerDay(1);
        props.setMaxActiveConversations(2);
        Clock clock = Clock.fixed(Instant.parse("2026-11-23T08:00:00Z"), ZoneOffset.UTC);
        MaintenanceRateLimiter limiter = new MaintenanceRateLimiter(props, clock);
        UUID userId = UUID.randomUUID();

        limiter.checkAndConsumeMessage(userId);
        limiter.checkAndConsumeMessage(userId);
        assertThatThrownBy(() -> limiter.checkAndConsumeMessage(userId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException app = (AppException) ex;
                    assertThat(app.getErrorCode()).isEqualTo(ErrorCode.MAINTENANCE_RATE_LIMITED);
                    assertThat(app.getErrorCode().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });

        limiter.checkAndConsumeUpload(userId);
        assertThatThrownBy(() -> limiter.checkAndConsumeUpload(userId))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_RATE_LIMITED);

        limiter.checkActiveConversations(userId, 1);
        assertThatThrownBy(() -> limiter.checkActiveConversations(userId, 2))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_RATE_LIMITED);
    }

    @Test
    void enforcesTenSecondRecheckCooldownPerConversationWithoutConsumingDailyMessageQuota() {
        MaintenanceRateLimitProperties props = new MaintenanceRateLimitProperties();
        props.setMessagesPerDay(1);
        final Instant[] now = {Instant.parse("2026-11-23T08:00:00Z")};
        Clock mutableClock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        MaintenanceRateLimiter limiter = new MaintenanceRateLimiter(props, mutableClock);
        UUID convA = UUID.randomUUID();
        UUID convB = UUID.randomUUID();

        assertThat(limiter.tryConsumeRecheck(convA)).isTrue();
        now[0] = now[0].plusSeconds(5);
        assertThat(limiter.tryConsumeRecheck(convA)).isFalse();
        assertThat(limiter.tryConsumeRecheck(convB)).isTrue(); // independent conversation

        now[0] = now[0].plusSeconds(5); // 10s elapsed since first convA recheck
        assertThat(limiter.tryConsumeRecheck(convA)).isTrue();
    }
}
