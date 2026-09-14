package com.hesta.backend.realtime.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketRealtimePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bind_withEnvironmentStyleOverride_usesConfiguredHeartbeat() {
        contextRunner
                .withSystemProperties("REALTIME_WEBSOCKET_HEARTBEAT=45s")
                .withPropertyValues("app.realtime.websocket.heartbeat=${REALTIME_WEBSOCKET_HEARTBEAT}")
                .run(context -> assertThat(context.getBean(WebSocketRealtimeProperties.class).heartbeat())
                        .isEqualTo(Duration.ofSeconds(45)));
    }

    @Test
    void bind_withNonPositiveHeartbeat_failsValidation() {
        contextRunner
                .withPropertyValues("app.realtime.websocket.heartbeat=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebSocketRealtimeProperties.class)
    static class TestConfig {
    }
}
