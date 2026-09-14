package com.hesta.backend.realtime.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.realtime.websocket")
public record WebSocketRealtimeProperties(
        @NotNull @DurationMin(seconds = 1) Duration heartbeat
) {
}
