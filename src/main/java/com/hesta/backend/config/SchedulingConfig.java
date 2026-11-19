package com.hesta.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulingConfig {

    // Named "taskScheduler" so @Scheduled uses this pool; without it Spring falls back to ONE thread
    // because two other TaskScheduler beans exist, and a slow job makes ScheduleRunner miss minutes.
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(@Value("${hesta.scheduler.pool-size:4}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("hesta-sched-");
        return scheduler;
    }
}
