package com.hesta.backend.service;

import com.hesta.backend.cache.HestaCacheService;
import com.hesta.backend.config.MaintenanceRateLimitProperties;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ giới hạn tần suất theo userId cho luồng trợ lý chẩn đoán và tải ảnh bảo trì.
 * Dùng Redis nguyên tử khi khả dụng và tự fail-open sang bộ đếm in-memory khi Redis tắt hoặc lỗi.
 */
@Component
@RequiredArgsConstructor
public class MaintenanceRateLimiter {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final long RECHECK_COOLDOWN_MS = 10_000L;

    private final MaintenanceRateLimitProperties properties;
    private final Clock clock;

    @Autowired(required = false)
    private HestaCacheService cacheService;

    public void setCacheService(HestaCacheService cacheService) {
        this.cacheService = cacheService;
    }

    private final ConcurrentHashMap<String, AtomicInteger> messageCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> uploadCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> llmCallCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> imageAnalysisCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastRecheckByConversation = new ConcurrentHashMap<>();

    public void checkAndConsumeMessage(UUID userId) {
        int limit = properties.getMessagesPerDay();
        if (limit <= 0) {
            return;
        }
        int count = increment("msg", messageCounters, key(userId));
        if (count > limit) {
            throw new AppException(ErrorCode.MAINTENANCE_RATE_LIMITED,
                    "Bạn đã đạt giới hạn " + limit + " tin nhắn trợ lý trong ngày. Vui lòng thử lại vào ngày mai hoặc chuyển sang form báo hỏng thủ công.");
        }
    }

    public void checkAndConsumeUpload(UUID userId) {
        int limit = properties.getUploadsPerDay();
        if (limit <= 0) {
            return;
        }
        int count = increment("upload", uploadCounters, key(userId));
        if (count > limit) {
            throw new AppException(ErrorCode.MAINTENANCE_RATE_LIMITED,
                    "Bạn đã đạt giới hạn tải lên " + limit + " ảnh bảo trì trong ngày. Vui lòng thử lại vào ngày mai.");
        }
    }

    public boolean tryConsumeLlmCall(UUID userId) {
        int limit = properties.getLlmCallsPerDay();
        if (limit <= 0) {
            return true;
        }
        int count = increment("llm", llmCallCounters, key(userId));
        return count <= limit;
    }

    public boolean tryConsumeImageAnalysis(UUID userId) {
        int imgLimit = properties.getImageAnalysesPerDay();
        if (imgLimit > 0 && increment("img", imageAnalysisCounters, key(userId)) > imgLimit) {
            return false;
        }
        return tryConsumeLlmCall(userId);
    }

    public void checkActiveConversations(UUID userId, long activeCount) {
        int limit = properties.getMaxActiveConversations();
        if (limit > 0 && activeCount >= limit) {
            throw new AppException(ErrorCode.MAINTENANCE_RATE_LIMITED,
                    "Bạn đang có " + activeCount + " phiên chẩn đoán chưa hoàn tất (tối đa " + limit + "). Vui lòng tiếp tục phiên cũ trước khi tạo phiên mới.");
        }
    }

    /**
     * Giới hạn nút RECHECK_STATUS tối đa 1 lần mỗi 10 giây cho mỗi hội thoại (không tính vào hạn mức tin nhắn/ngày).
     */
    public boolean tryConsumeRecheck(UUID conversationId) {
        if (conversationId == null) {
            return true;
        }
        if (cacheService != null) {
            Optional<Boolean> redisAllowed = cacheService.setIfAbsentWithTtl(
                    "ratelimit:recheck",
                    conversationId.toString(),
                    "1",
                    Duration.ofMillis(RECHECK_COOLDOWN_MS)
            );
            if (redisAllowed.isPresent()) {
                return redisAllowed.get();
            }
        }
        long nowMs = clock.millis();
        if (lastRecheckByConversation.size() > 10_000) {
            lastRecheckByConversation.entrySet().removeIf(e -> nowMs - e.getValue() > RECHECK_COOLDOWN_MS * 6);
        }
        boolean[] allowed = {false};
        lastRecheckByConversation.compute(conversationId, (id, prevMs) -> {
            if (prevMs == null || nowMs - prevMs >= RECHECK_COOLDOWN_MS) {
                allowed[0] = true;
                return nowMs;
            }
            return prevMs;
        });
        return allowed[0];
    }

    private int increment(String bucket, ConcurrentHashMap<String, AtomicInteger> store, String key) {
        if (cacheService != null) {
            OptionalLong redisCount = cacheService.incrementWithTtl(
                    "ratelimit:" + bucket,
                    key,
                    ttlUntilNextDay()
            );
            if (redisCount.isPresent()) {
                return (int) Math.min(Integer.MAX_VALUE, redisCount.getAsLong());
            }
        }
        if (store.size() > 10_000) {
            String todayPrefix = today() + ":";
            store.keySet().removeIf(k -> !k.startsWith(todayPrefix));
        }
        return store.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    private Duration ttlUntilNextDay() {
        var nowZoned = clock.instant().atZone(BUSINESS_ZONE);
        var nextMidnight = nowZoned.toLocalDate().plusDays(1).atStartOfDay(BUSINESS_ZONE);
        long seconds = Math.max(60L, Duration.between(nowZoned, nextMidnight).getSeconds() + 60L);
        return Duration.ofSeconds(seconds);
    }

    private String key(UUID userId) {
        return today() + ":" + userId;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }
}
