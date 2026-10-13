package com.hesta.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TwinHealthPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class)
            .withPropertyValues("app.twin.health.stale-after=30s", "app.twin.health.offline-after=5m",
                    "app.twin.health.evaluation-interval=2s");

    @Test
    void bind_explicitDurations_bindsTypedValues() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(TwinHealthProperties.class);
            assertThat(properties.staleAfter()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.offlineAfter()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.evaluationInterval()).isEqualTo(Duration.ofSeconds(2));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"stale-after=0s", "stale-after=-1s", "offline-after=30s", "offline-after=1s",
            "offline-after=-1s", "evaluation-interval=0s", "evaluation-interval=-1s", "stale-after=not-a-duration"})
    void bind_invalidThresholds_failsStartup(String invalid) {
        runner.withPropertyValues("app.twin.health." + invalid).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bind_environmentOverrides_usesDocumentedNames() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                        "APP_TWIN_HEALTH_STALEAFTER", "45s", "APP_TWIN_HEALTH_OFFLINEAFTER", "6m",
                        "APP_TWIN_HEALTH_EVALUATIONINTERVAL", "3s", "APP_TWIN_HEALTH_SCHEDULINGENABLED", "false"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(TwinHealthProperties.class);
                    assertThat(properties.staleAfter()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(properties.offlineAfter()).isEqualTo(Duration.ofMinutes(6));
                    assertThat(properties.evaluationInterval()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.schedulingEnabled()).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TwinHealthProperties.class)
    static class Config { }
}
