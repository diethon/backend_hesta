package com.hesta.backend.config.mqtt;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs automation off the MQTT callback thread. Device ACKs are delivered on that same thread,
 * so waiting for them there blocks until the command timeout and stalls all MQTT ingest.
 * Not exposed as an Executor bean on purpose: that would disable Spring Boot's applicationTaskExecutor.
 */
@Component
@Slf4j
public class AutomationDispatcher {
    private final ThreadPoolExecutor pool;

    public AutomationDispatcher(@Value("${hesta.automation.executor.pool-size:4}") int poolSize,
                                @Value("${hesta.automation.executor.queue-capacity:500}") int queueCapacity) {
        AtomicInteger counter = new AtomicInteger();
        pool = new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "automation-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                (task, executor) -> log.warn("Automation queue full ({}), dropping event", queueCapacity));
    }

    public void dispatch(Runnable task) {
        pool.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                log.warn("Automation task failed: {}", ex.getMessage());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }
}
