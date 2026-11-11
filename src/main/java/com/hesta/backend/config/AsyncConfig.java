package com.hesta.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "anomalyNotificationExecutor")
    public Executor anomalyNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("anomaly-notif-");
        executor.setRejectedExecutionHandler((r, exec) ->
                log.warn("Anomaly notification queue full! Discarding task or running on caller thread. Active: {}, QueueSize: {}",
                        exec.getActiveCount(), exec.getQueue().size())
        );
        executor.initialize();
        return executor;
    }
}
