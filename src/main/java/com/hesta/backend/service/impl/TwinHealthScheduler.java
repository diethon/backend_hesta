package com.hesta.backend.service.impl;

import com.hesta.backend.config.TwinHealthProperties;
import com.hesta.backend.service.TwinHealthEvaluationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "app.twin.health.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class TwinHealthScheduler implements SchedulingConfigurer {
    private final TwinHealthEvaluationService evaluator;
    private final TwinHealthProperties properties;
    private final TaskScheduler scheduler;

    public TwinHealthScheduler(TwinHealthEvaluationService evaluator, TwinHealthProperties properties,
                               @Qualifier("twinHealthTaskScheduler") TaskScheduler scheduler) {
        this.evaluator = evaluator;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(scheduler);
        registrar.addFixedDelayTask(new FixedDelayTask(evaluator::evaluateAll,
                properties.evaluationInterval(), Duration.ZERO));
    }
}
