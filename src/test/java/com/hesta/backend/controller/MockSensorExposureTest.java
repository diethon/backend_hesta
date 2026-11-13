package com.hesta.backend.controller;

import com.hesta.backend.service.SensorReadingIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class MockSensorExposureTest {
    @Mock SensorReadingIngestionService service;

    @Test
    void controller_requiresBothMockProfileAndEnableFlag() {
        context().run(ctx -> assertThat(ctx).doesNotHaveBean(MockSensorController.class));
        context("mock-sensors").run(ctx -> assertThat(ctx).doesNotHaveBean(MockSensorController.class));
        context().withPropertyValues("app.mock-sensors.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MockSensorController.class));
        context("mock-sensors").withPropertyValues("app.mock-sensors.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(MockSensorController.class));
    }

    @Test
    void controller_productionProfileAlwaysDisablesMockAdapter() {
        for (String production : new String[]{"prod", "production"}) {
            context("mock-sensors", production).withPropertyValues("app.mock-sensors.enabled=true")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(MockSensorController.class));
        }
    }

    private ApplicationContextRunner context(String... profiles) {
        return new ApplicationContextRunner().withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profiles))
                .withBean(SensorReadingIngestionService.class, () -> service)
                .withUserConfiguration(MockSensorController.class);
    }
}
