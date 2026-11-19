package com.hesta.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TwinHealthProperties.class)
@EnableScheduling
public class TwinHealthConfig {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock twinHealthClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "app.twin.health.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    ThreadPoolTaskScheduler twinHealthTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("twin-health-");
        return scheduler;
    }
}
