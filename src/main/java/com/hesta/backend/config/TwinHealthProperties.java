package com.hesta.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Defaults are defined only here. Binding fails before the scheduler can start. */
@Validated
@ConfigurationProperties("app.twin.health")
public record TwinHealthProperties(
        @DefaultValue("1m") Duration staleAfter,
        @DefaultValue("5m") Duration offlineAfter,
        @DefaultValue("15s") Duration evaluationInterval,
        @DefaultValue("true") boolean schedulingEnabled
) {
    public TwinHealthProperties {
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("app.twin.health.stale-after must be positive");
        }
        if (offlineAfter == null || offlineAfter.compareTo(staleAfter) <= 0) {
            throw new IllegalArgumentException("app.twin.health.offline-after must be greater than stale-after");
        }
        if (evaluationInterval == null || evaluationInterval.isZero() || evaluationInterval.isNegative()) {
            throw new IllegalArgumentException("app.twin.health.evaluation-interval must be positive");
        }
    }
}
