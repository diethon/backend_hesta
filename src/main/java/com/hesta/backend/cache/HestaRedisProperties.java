package com.hesta.backend.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties governing HESTA Redis caching, key namespacing, and fail-open behavior.
 */
@Data
@Component
@ConfigurationProperties(prefix = "hesta.redis")
public class HestaRedisProperties {

    private boolean enabled = false;
    private boolean localCacheEnabled = true;
    private String keyPrefix = "hesta:";
    private long circuitBreakerCooldownMs = 15_000L;
    private int maxLocalFallbackEntries = 2_000;
    private int scanBatchSize = 200;
}
