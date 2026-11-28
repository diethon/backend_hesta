package com.hesta.backend.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Unified Redis + bounded in-memory fail-open cache and rate-limit primitive service.
 * Uses plain JSON (without polymorphic {@code @class} typing), non-blocking {@code SCAN}/{@code UNLINK},
 * atomic Lua {@code INCR + EXPIRE}, and a cooldown circuit breaker on Redis errors.
 */
@Slf4j
@Service
public class HestaCacheService {

    private static final int UNLINK_CHUNK_SIZE = 200;

    private static final DefaultRedisScript<Long> INCR_WITH_TTL_SCRIPT;

    static {
        INCR_WITH_TTL_SCRIPT = new DefaultRedisScript<>();
        INCR_WITH_TTL_SCRIPT.setScriptText(
                "local current = redis.call('INCR', KEYS[1])\n"
                + "if current == 1 then\n"
                + "  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))\n"
                + "elseif redis.call('TTL', KEYS[1]) == -1 then\n"
                + "  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))\n"
                + "end\n"
                + "return current"
        );
        INCR_WITH_TTL_SCRIPT.setResultType(Long.class);
    }

    private record LocalEntry(Object value, long expiresAtMs) {
        boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }

    @FunctionalInterface
    private interface JsonReader<T> {
        T read(String json) throws JsonProcessingException;
    }

    private final HestaRedisProperties properties;
    private final ObjectMapper cacheMapper;
    private final StringRedisTemplate redisTemplate;
    private final Map<String, LocalEntry> localCache = new ConcurrentHashMap<>();
    private final AtomicLong circuitOpenUntilMs = new AtomicLong(0L);

    @Autowired
    public HestaCacheService(
            HestaRedisProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("hestaStringRedisTemplate") @Nullable StringRedisTemplate redisTemplate) {
        this.properties = properties != null ? properties : new HestaRedisProperties();
        this.redisTemplate = redisTemplate;
        this.cacheMapper = buildSafeMapper(objectMapper);
    }

    /** Creates an in-memory-only instance for standalone tests when Redis is absent. */
    public static HestaCacheService inMemoryOnly() {
        return new HestaCacheService(new HestaRedisProperties(), new ObjectMapper(), null);
    }

    private static ObjectMapper buildSafeMapper(ObjectMapper base) {
        ObjectMapper mapper = base != null ? base.copy() : new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    public boolean isRedisActive() {
        return properties.isEnabled()
                && redisTemplate != null
                && System.currentTimeMillis() >= circuitOpenUntilMs.get();
    }

    public boolean isReadThroughCacheEnabled() {
        return isRedisActive() || properties.isLocalCacheEnabled();
    }

    public <T> Optional<T> get(String namespace, String key, Class<T> type) {
        if (type == null) {
            return Optional.empty();
        }
        String fullKey = buildKey(namespace, key);
        return getInternal(
                fullKey,
                json -> cacheMapper.readValue(json, type),
                raw -> type.isInstance(raw) ? Optional.of(type.cast(raw)) : Optional.empty()
        );
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String namespace, String key, TypeReference<T> typeRef) {
        if (typeRef == null) {
            return Optional.empty();
        }
        String fullKey = buildKey(namespace, key);
        return getInternal(
                fullKey,
                json -> cacheMapper.readValue(json, typeRef),
                raw -> raw != null ? Optional.of((T) raw) : Optional.empty()
        );
    }

    public void put(String namespace, String key, Object value, Duration ttl) {
        if (value == null || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        String fullKey = buildKey(namespace, key);
        if (isRedisActive()) {
            try {
                String json = cacheMapper.writeValueAsString(value);
                redisTemplate.opsForValue().set(fullKey, json, ttl);
                localCache.remove(fullKey);
                return;
            } catch (JsonProcessingException jsonEx) {
                log.warn("Failed to serialize cache entry for [{}], storing locally: {}", fullKey, jsonEx.getMessage());
            } catch (RuntimeException ex) {
                tripCircuit("put(" + fullKey + ")", ex);
            }
        }
        putLocal(fullKey, value, ttl);
    }

    public void evict(String namespace, String key) {
        String fullKey = buildKey(namespace, key);
        doEvict(fullKey);
        scheduleAfterCommit(() -> doEvict(fullKey));
    }

    public void evictByPrefix(String namespace, String keyPrefix) {
        String fullPrefix = buildKey(namespace, keyPrefix);
        doEvictByPrefix(fullPrefix);
        scheduleAfterCommit(() -> doEvictByPrefix(fullPrefix));
    }

    private void doEvict(String fullKey) {
        localCache.remove(fullKey);
        if (!isRedisActive()) {
            return;
        }
        try {
            redisTemplate.unlink(fullKey);
        } catch (RuntimeException ex) {
            tripCircuit("evict(" + fullKey + ")", ex);
        }
    }

    private void doEvictByPrefix(String fullPrefix) {
        localCache.keySet().removeIf(k -> k.startsWith(fullPrefix));
        if (!isRedisActive()) {
            return;
        }
        int batchSize = Math.max(10, properties.getScanBatchSize());
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                ScanOptions options = ScanOptions.scanOptions()
                        .match(fullPrefix + "*")
                        .count(batchSize)
                        .build();
                List<byte[]> batch = new ArrayList<>(UNLINK_CHUNK_SIZE);
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext()) {
                        batch.add(cursor.next());
                        if (batch.size() >= UNLINK_CHUNK_SIZE) {
                            connection.keyCommands().unlink(batch.toArray(new byte[0][]));
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        connection.keyCommands().unlink(batch.toArray(new byte[0][]));
                    }
                }
                return null;
            });
        } catch (RuntimeException ex) {
            tripCircuit("evictByPrefix(" + fullPrefix + ")", ex);
        }
    }

    private void scheduleAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        }
    }

    public void clearNamespace(String namespace) {
        evictByPrefix(namespace, "");
    }

    public OptionalLong incrementWithTtl(String namespace, String key, Duration ttl) {
        if (!isRedisActive() || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return OptionalLong.empty();
        }
        String fullKey = buildKey(namespace, key);
        long ttlSeconds = Math.max(1L, ttl.toSeconds());
        try {
            Long value = redisTemplate.execute(
                    INCR_WITH_TTL_SCRIPT,
                    Collections.singletonList(fullKey),
                    String.valueOf(ttlSeconds)
            );
            return value != null ? OptionalLong.of(value) : OptionalLong.empty();
        } catch (RuntimeException ex) {
            tripCircuit("incrementWithTtl(" + fullKey + ")", ex);
            return OptionalLong.empty();
        }
    }

    public Optional<Boolean> setIfAbsentWithTtl(String namespace, String key, String value, Duration ttl) {
        if (!isRedisActive() || value == null || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return Optional.empty();
        }
        String fullKey = buildKey(namespace, key);
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(fullKey, value, ttl);
            return Optional.of(Boolean.TRUE.equals(acquired));
        } catch (RuntimeException ex) {
            tripCircuit("setIfAbsentWithTtl(" + fullKey + ")", ex);
            return Optional.empty();
        }
    }

    private <T> Optional<T> getInternal(
            String fullKey,
            JsonReader<T> jsonReader,
            Function<Object, Optional<T>> localCaster) {
        if (isRedisActive()) {
            try {
                String json = redisTemplate.opsForValue().get(fullKey);
                if (json == null) {
                    return Optional.empty();
                }
                try {
                    T parsed = jsonReader.read(json);
                    return Optional.ofNullable(parsed);
                } catch (JsonProcessingException jsonEx) {
                    log.debug("Corrupt cached JSON for [{}], self-healing unlink: {}", fullKey, jsonEx.getMessage());
                    safeUnlink(fullKey);
                    return Optional.empty();
                }
            } catch (RuntimeException ex) {
                tripCircuit("get(" + fullKey + ")", ex);
            }
        }
        return getLocal(fullKey, localCaster);
    }

    private <T> Optional<T> getLocal(String fullKey, Function<Object, Optional<T>> localCaster) {
        if (!properties.isLocalCacheEnabled()) {
            return Optional.empty();
        }
        LocalEntry entry = localCache.get(fullKey);
        if (entry == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (entry.isExpired(now)) {
            localCache.remove(fullKey, entry);
            return Optional.empty();
        }
        return localCaster.apply(entry.value());
    }

    private void putLocal(String fullKey, Object value, Duration ttl) {
        if (!properties.isLocalCacheEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + ttl.toMillis();
        int maxEntries = Math.max(1, properties.getMaxLocalFallbackEntries());
        if (localCache.size() >= maxEntries) {
            localCache.entrySet().removeIf(e -> e.getValue().isExpired(now));
        }
        while (localCache.size() >= maxEntries) {
            var it = localCache.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            localCache.remove(it.next());
        }
        localCache.put(fullKey, new LocalEntry(value, expiresAt));
    }

    private void safeUnlink(String fullKey) {
        try {
            redisTemplate.unlink(fullKey);
        } catch (RuntimeException ex) {
            tripCircuit("unlink(" + fullKey + ")", ex);
        }
    }

    private void tripCircuit(String operation, Throwable ex) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(1L, properties.getCircuitBreakerCooldownMs());
        long openUntil = now + cooldown;
        long prevOpenUntil = circuitOpenUntilMs.getAndSet(openUntil);
        if (now >= prevOpenUntil) {
            log.warn("Redis circuit breaker OPEN for {} ms after failure on {}: {}",
                    cooldown, operation, ex != null ? ex.getMessage() : "unknown");
        } else {
            log.debug("Redis operation {} failed while circuit already open: {}",
                    operation, ex != null ? ex.getMessage() : "unknown");
        }
    }

    private String buildKey(String namespace, String key) {
        String prefix = properties.getKeyPrefix() != null ? properties.getKeyPrefix() : "";
        String ns = namespace != null ? namespace : "";
        String k = key != null ? key : "";
        return prefix + ns + ":" + k;
    }
}
