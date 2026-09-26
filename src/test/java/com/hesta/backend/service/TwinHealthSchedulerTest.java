package com.hesta.backend.service;

import com.hesta.backend.service.impl.TwinHealthScheduler;
import com.hesta.backend.support.TwinHealthTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TwinHealthSchedulerTest {
    @Mock TwinHealthEvaluationService evaluator;
    @Mock TaskScheduler taskScheduler;

    @Test
    void configureTasks_registersOneCentralTaskAtConfiguredInterval() {
        var properties = TwinHealthTestSupport.properties();
        var scheduler = new TwinHealthScheduler(evaluator, properties, taskScheduler);
        var registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);
        assertThat(registrar.getFixedDelayTaskList()).singleElement().satisfies(task -> {
            assertThat(task.getIntervalDuration()).isEqualTo(properties.evaluationInterval());
            assertThat(task.getInitialDelayDuration()).isEqualTo(java.time.Duration.ZERO);
            task.getRunnable().run();
        });
        verify(evaluator).evaluateAll();
        assertThat(registrar.getScheduler()).isSameAs(taskScheduler);
        registrar.destroy();
    }
}
