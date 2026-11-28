package com.hesta.backend.cache;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Production Lettuce Redis configuration activated only when {@code hesta.redis.enabled=true}.
 * Enforces bounded command/connect timeouts and immediate command rejection while disconnected
 * so Redis downtime never stalls application threads.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "hesta.redis.enabled", havingValue = "true")
public class RedisConfig {

    @Bean
    @Primary
    public LettuceConnectionFactory hestaRedisConnectionFactory(RedisProperties redisProperties) {
        String host = (redisProperties.getHost() != null && !redisProperties.getHost().isBlank())
                ? redisProperties.getHost().trim()
                : "localhost";
        RedisStandaloneConfiguration standaloneConfig =
                new RedisStandaloneConfiguration(host, redisProperties.getPort());
        standaloneConfig.setDatabase(redisProperties.getDatabase());

        if (redisProperties.getUsername() != null && !redisProperties.getUsername().isBlank()) {
            standaloneConfig.setUsername(redisProperties.getUsername().trim());
        }
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            standaloneConfig.setPassword(RedisPassword.of(redisProperties.getPassword()));
        }

        Duration commandTimeout = redisProperties.getTimeout() != null
                ? redisProperties.getTimeout()
                : Duration.ofMillis(1500);
        Duration connectTimeout = redisProperties.getConnectTimeout() != null
                ? redisProperties.getConnectTimeout()
                : Duration.ofMillis(1500);

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(connectTimeout)
                .keepAlive(true)
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(socketOptions)
                .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout)
                .shutdownTimeout(Duration.ofMillis(200))
                .clientOptions(clientOptions)
                .build();

        return new LettuceConnectionFactory(standaloneConfig, clientConfig);
    }

    @Bean("hestaStringRedisTemplate")
    public StringRedisTemplate hestaStringRedisTemplate(
            @Qualifier("hestaRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logRedisStartupHealth(ApplicationReadyEvent event) {
        try {
            RedisConnectionFactory factory = event.getApplicationContext()
                    .getBean("hestaRedisConnectionFactory", RedisConnectionFactory.class);
            try (var connection = factory.getConnection()) {
                String pong = connection.ping();
                log.info("Redis startup health check succeeded: {}", pong);
            }
        } catch (Exception ex) {
            log.warn("Redis startup health check failed (failing open to in-memory fallback): {}", ex.getMessage());
        }
    }
}
