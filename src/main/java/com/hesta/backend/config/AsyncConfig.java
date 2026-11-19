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

    /**
     * AFTER_COMMIT listeners that open their own transaction (twin health, parental guardian). They must not run on
     * the committing thread: it still holds that transaction's DB connection, so each device command needed two at
     * once and concurrent scenes exhausted the pool. One thread keeps a device's state changes in order.
     */
    @Bean(name = "deviceStateEventExecutor")
    public Executor deviceStateEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("device-state-");
        executor.setRejectedExecutionHandler((r, exec) ->
                log.warn("Device state event queue full ({}), dropping one listener run", exec.getQueue().size()));
        executor.initialize();
        return executor;
    }
}
