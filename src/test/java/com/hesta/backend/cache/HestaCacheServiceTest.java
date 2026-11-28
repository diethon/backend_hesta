package com.hesta.backend.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.MaintenanceRateLimitProperties;
import com.hesta.backend.dto.response.DeviceEnergyBreakdown;
import com.hesta.backend.dto.response.DeviceStateHistoryResponse;
import com.hesta.backend.dto.response.DeviceUptimeResponse;
import com.hesta.backend.dto.response.EnergyAnalyticsResponse;
import com.hesta.backend.dto.response.EnergySummaryResponse;
import com.hesta.backend.dto.response.EnergyTimeSeriesPoint;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.service.MaintenanceRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HestaCacheServiceTest {

    @Test
    void inMemoryFallback_supportsPutGetEvictPrefixExpiryAndCap() throws InterruptedException {
        HestaRedisProperties props = new HestaRedisProperties();
        props.setEnabled(false);
        props.setMaxLocalFallbackEntries(2);
        HestaCacheService cache = new HestaCacheService(props, new ObjectMapper(), null);

        assertThat(cache.isRedisActive()).isFalse();

        cache.put("energy:home", "h1:24H", "v1", Duration.ofSeconds(10));
        cache.put("energy:home", "h1:7D", "v2", Duration.ofSeconds(10));
        assertThat(cache.get("energy:home", "h1:24H", String.class)).contains("v1");

        // Cap = 2: putting a 3rd entry evicts one existing entry so size stays bounded
        cache.put("energy:home", "h2:24H", "v3", Duration.ofSeconds(10));
        assertThat(cache.get("energy:home", "h2:24H", String.class)).contains("v3");
        int presentCount = 0;
        if (cache.get("energy:home", "h1:24H", String.class).isPresent()) presentCount++;
        if (cache.get("energy:home", "h1:7D", String.class).isPresent()) presentCount++;
        if (cache.get("energy:home", "h2:24H", String.class).isPresent()) presentCount++;
        assertThat(presentCount).isEqualTo(2);

        cache.evictByPrefix("energy:home", "h1:");
        assertThat(cache.get("energy:home", "h1:24H", String.class)).isEmpty();
        assertThat(cache.get("energy:home", "h1:7D", String.class)).isEmpty();

        cache.put("device:uptime", "d1:7", "short", Duration.ofMillis(20));
        Thread.sleep(45);
        assertThat(cache.get("device:uptime", "d1:7", String.class)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisActive_roundTripsEnergyUptimeAndHistoryDtosWithoutPolymorphicClass() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);

        Map<String, String> store = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        when(template.unlink(anyString())).thenAnswer(inv -> store.remove(inv.<String>getArgument(0)) != null);

        HestaRedisProperties props = new HestaRedisProperties();
        props.setEnabled(true);
        HestaCacheService cache = new HestaCacheService(props, new ObjectMapper(), template);

        UUID devId = UUID.randomUUID();
        OffsetDateTime ts = OffsetDateTime.parse("2026-11-28T00:15:00+07:00");

        EnergyAnalyticsResponse analytics = EnergyAnalyticsResponse.builder()
                .summary(EnergySummaryResponse.builder().totalKwh(12.5).estimatedCostVnd(25000L).period("24H").build())
                .timeSeries(List.of(EnergyTimeSeriesPoint.builder().timestamp(ts).label("00:00").kwh(1.2).costVnd(2400L).build()))
                .breakdown(List.of(DeviceEnergyBreakdown.builder().deviceId(devId).deviceName("AC").kwh(10.0).wattage(1500).build()))
                .build();
        cache.put("energy:home", "home1:24H:ALL", analytics, Duration.ofSeconds(30));

        String rawAnalyticsJson = store.get("hesta:energy:home:home1:24H:ALL");
        assertThat(rawAnalyticsJson).isNotNull().doesNotContain("@class");

        Optional<EnergyAnalyticsResponse> loadedAnalytics =
                cache.get("energy:home", "home1:24H:ALL", EnergyAnalyticsResponse.class);
        assertThat(loadedAnalytics).isPresent();
        assertThat(loadedAnalytics.get().getSummary().getTotalKwh()).isEqualTo(12.5);
        assertThat(loadedAnalytics.get().getTimeSeries().get(0).getTimestamp().toInstant()).isEqualTo(ts.toInstant());
        assertThat(loadedAnalytics.get().getBreakdown().get(0).getDeviceId()).isEqualTo(devId);

        DeviceUptimeResponse uptime = DeviceUptimeResponse.builder()
                .uptimePercent(85.5)
                .totalHours(168.0)
                .onHours(143.6)
                .offHours(24.4)
                .timelinePoints(List.of(DeviceUptimeResponse.TimelinePoint.builder()
                        .hour(10).timestamp("10:00").state("ON").durationSeconds(1800L).build()))
                .build();
        cache.put("device:uptime", devId + ":7", uptime, Duration.ofSeconds(30));
        Optional<DeviceUptimeResponse> loadedUptime =
                cache.get("device:uptime", devId + ":7", DeviceUptimeResponse.class);
        assertThat(loadedUptime).isPresent();
        assertThat(loadedUptime.get().getTimelinePoints()).hasSize(1);

        List<DeviceStateHistoryResponse> histories = List.of(
                DeviceStateHistoryResponse.builder()
                        .id(UUID.randomUUID())
                        .deviceId(devId)
                        .previousState(Map.of("power", "OFF"))
                        .newState(Map.of("power", "ON"))
                        .source("MANUAL")
                        .isTest(false)
                        .changedAt(ts)
                        .build()
        );
        cache.put("device:history", devId.toString(), histories, Duration.ofSeconds(15));
        Optional<List<DeviceStateHistoryResponse>> loadedHistory = cache.get(
                "device:history",
                devId.toString(),
                new TypeReference<List<DeviceStateHistoryResponse>>() {}
        );
        assertThat(loadedHistory).isPresent();
        assertThat(loadedHistory.get()).hasSize(1);
        assertThat(loadedHistory.get().get(0).getNewState()).containsEntry("power", "ON");
        assertThat(loadedHistory.get().get(0).getChangedAt().toInstant()).isEqualTo(ts.toInstant());

        cache.evict("device:history", devId.toString());
        verify(template).unlink("hesta:device:history:" + devId);
        assertThat(store).doesNotContainKey("hesta:device:history:" + devId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisPrimitives_incrementAndSetIfAbsentWorkAtomically() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(template.execute(any(RedisScript.class), anyList(), any())).thenReturn(3L);
        when(ops.setIfAbsent("hesta:ratelimit:recheck:c1", "1", Duration.ofSeconds(10))).thenReturn(true);

        HestaRedisProperties props = new HestaRedisProperties();
        props.setEnabled(true);
        HestaCacheService cache = new HestaCacheService(props, new ObjectMapper(), template);

        OptionalLong count = cache.incrementWithTtl("ratelimit:msg", "k1", Duration.ofSeconds(120));
        assertThat(count).hasValue(3L);
        verify(template).execute(any(RedisScript.class), eq(List.of("hesta:ratelimit:msg:k1")), eq("120"));

        Optional<Boolean> acquired = cache.setIfAbsentWithTtl("ratelimit:recheck", "c1", "1", Duration.ofSeconds(10));
        assertThat(acquired).contains(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void circuitBreaker_tripsOnRedisFailureFallsBackAndRecoversAfterCooldown() throws InterruptedException {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));

        HestaRedisProperties props = new HestaRedisProperties();
        props.setEnabled(true);
        props.setCircuitBreakerCooldownMs(50L);
        HestaCacheService cache = new HestaCacheService(props, new ObjectMapper(), template);

        // First call fails against Redis and opens the circuit cleanly without throwing
        assertThat(cache.get("ns", "k1", String.class)).isEmpty();
        assertThat(cache.isRedisActive()).isFalse();

        // While circuit is open, writes and reads stay in local fallback without hitting Redis
        cache.put("ns", "k1", "fallback-val", Duration.ofSeconds(5));
        assertThat(cache.get("ns", "k1", String.class)).contains("fallback-val");
        verify(ops, times(1)).get(anyString());
        verify(ops, times(0)).set(anyString(), anyString(), any(Duration.class));

        // After cooldown elapses, Redis is probed again and resumes serving
        Thread.sleep(75);
        assertThat(cache.isRedisActive()).isTrue();
        org.mockito.Mockito.reset(ops);
        when(ops.get("hesta:ns:k2")).thenReturn("\"recovered\"");
        assertThat(cache.get("ns", "k2", String.class)).contains("recovered");
    }

    @Test
    @SuppressWarnings("unchecked")
    void corruptJson_isSelfHealedViaUnlinkAndReturnsEmpty() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get("hesta:ns:bad")).thenReturn("{not-valid-json");

        HestaRedisProperties props = new HestaRedisProperties();
        props.setEnabled(true);
        HestaCacheService cache = new HestaCacheService(props, new ObjectMapper(), template);

        assertThat(cache.get("ns", "bad", DeviceUptimeResponse.class)).isEmpty();
        verify(template).unlink("hesta:ns:bad");
    }

    @Test
    @SuppressWarnings("unchecked")
    void maintenanceRateLimiter_usesRedisWhenAvailableAndFailsOpenOnError() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);

        AtomicInteger redisSeq = new AtomicInteger(0);
        when(template.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(inv -> (long) redisSeq.incrementAndGet());
        when(ops.setIfAbsent(anyString(), eq("1"), eq(Duration.ofMillis(10_000L))))
                .thenReturn(true, false);

        HestaRedisProperties redisProps = new HestaRedisProperties();
        redisProps.setEnabled(true);
        redisProps.setCircuitBreakerCooldownMs(5_000L);
        HestaCacheService cache = new HestaCacheService(redisProps, new ObjectMapper(), template);

        MaintenanceRateLimitProperties limitProps = new MaintenanceRateLimitProperties();
        limitProps.setMessagesPerDay(2);
        Clock clock = Clock.fixed(Instant.parse("2026-11-28T05:00:00Z"), ZoneOffset.UTC);

        MaintenanceRateLimiter limiter = new MaintenanceRateLimiter(limitProps, clock);
        limiter.setCacheService(cache);

        UUID userId = UUID.randomUUID();
        limiter.checkAndConsumeMessage(userId);
        limiter.checkAndConsumeMessage(userId);
        assertThatThrownBy(() -> limiter.checkAndConsumeMessage(userId))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MAINTENANCE_RATE_LIMITED);

        UUID convId = UUID.randomUUID();
        assertThat(limiter.tryConsumeRecheck(convId)).isTrue();
        assertThat(limiter.tryConsumeRecheck(convId)).isFalse();

        // Simulate Redis failure -> trips circuit & falls back cleanly to in-memory counter
        when(template.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        UUID user2 = UUID.randomUUID();
        limiter.checkAndConsumeMessage(user2);
        limiter.checkAndConsumeMessage(user2);
        assertThatThrownBy(() -> limiter.checkAndConsumeMessage(user2))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MAINTENANCE_RATE_LIMITED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void customUserDetailsService_cachesPrincipalWithoutPasswordAndEvicts() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);

        Map<String, String> store = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        when(template.unlink(anyString())).thenAnswer(inv -> store.remove(inv.<String>getArgument(0)) != null);

        HestaRedisProperties props = new HestaRedisProperties();
        props.setEnabled(true);
        HestaCacheService cache = new HestaCacheService(props, new ObjectMapper(), template);

        UUID userId = UUID.randomUUID();
        com.hesta.backend.entity.User user = com.hesta.backend.entity.User.builder()
                .id(userId)
                .email("user@hesta.homes")
                .passwordHash("$2a$10$secretBcryptHashShouldNotBeCached")
                .status(com.hesta.backend.enums.AccountStatus.ACTIVE)
                .platformRole(com.hesta.backend.enums.PlatformRole.USER)
                .build();

        com.hesta.backend.repository.UserRepository userRepo = mock(com.hesta.backend.repository.UserRepository.class);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));

        com.hesta.backend.security.CustomUserDetailsService svc =
                new com.hesta.backend.security.CustomUserDetailsService(userRepo);
        svc.setCacheService(cache);

        var first = svc.loadUserById(userId);
        var second = svc.loadUserById(userId);

        assertThat(first.getUsername()).isEqualTo("user@hesta.homes");
        assertThat(second.getUsername()).isEqualTo("user@hesta.homes");
        assertThat(second.isEnabled()).isTrue();
        assertThat(second.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        verify(userRepo, times(1)).findById(userId);

        String cachedJson = store.get("hesta:auth:user:" + userId);
        assertThat(cachedJson).isNotNull().doesNotContain("secretBcryptHashShouldNotBeCached").doesNotContain("password");

        svc.evictUserCache(userId);
        verify(template).unlink("hesta:auth:user:" + userId);
        svc.loadUserById(userId);
        verify(userRepo, times(2)).findById(userId);
    }

    @Test
    void energyAnalytics_usesBatchedLatestBeforeQueryInsteadOfPerDeviceNPlusOne() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        com.hesta.backend.entity.Room room = com.hesta.backend.entity.Room.builder()
                .id(UUID.randomUUID()).name("Room").build();

        List<com.hesta.backend.entity.Device> devices = List.of(
                com.hesta.backend.entity.Device.builder().id(UUID.randomUUID()).name("AC").deviceType("AIR_CONDITIONER")
                        .status(com.hesta.backend.enums.DeviceStatus.ONLINE).room(room).currentState(Map.of("power", "ON")).build(),
                com.hesta.backend.entity.Device.builder().id(UUID.randomUUID()).name("Light").deviceType("LIGHT")
                        .status(com.hesta.backend.enums.DeviceStatus.ONLINE).room(room).currentState(Map.of("power", "ON")).build(),
                com.hesta.backend.entity.Device.builder().id(UUID.randomUUID()).name("TV").deviceType("TV")
                        .status(com.hesta.backend.enums.DeviceStatus.ONLINE).room(room).currentState(Map.of("power", "ON")).build()
        );

        com.hesta.backend.repository.DeviceRepository devRepo = mock(com.hesta.backend.repository.DeviceRepository.class);
        com.hesta.backend.repository.DeviceStateHistoryRepository histRepo = mock(com.hesta.backend.repository.DeviceStateHistoryRepository.class);
        com.hesta.backend.service.HomeAuthorizationService authService = mock(com.hesta.backend.service.HomeAuthorizationService.class);

        when(devRepo.findByHomeIdWithRoomAndNode(homeId)).thenReturn(devices);
        when(histRepo.findByDeviceIdsAndChangedAtBetween(anyList(), any(), any())).thenReturn(List.of());
        when(histRepo.findLatestBeforeByDeviceIds(anyList(), any())).thenReturn(List.of());

        com.hesta.backend.service.impl.EnergyAnalyticsServiceImpl analytics =
                new com.hesta.backend.service.impl.EnergyAnalyticsServiceImpl(devRepo, histRepo, authService);

        var res = analytics.getHomeEnergyAnalytics(userId, homeId, "24H");
        assertThat(res.getSummary().getTotalDeviceCount()).isEqualTo(3);

        verify(histRepo, org.mockito.Mockito.atLeastOnce()).findLatestBeforeByDeviceIds(anyList(), any());
        verify(histRepo, org.mockito.Mockito.never())
                .findFirstByDeviceIdAndChangedAtLessThanOrderByChangedAtDesc(any(), any());
    }

    @Test
    void localCacheDisabled_skipsLocalPutAndGetWhenRedisInactive() {
        HestaRedisProperties props = new HestaRedisProperties();
        props.setEnabled(false);
        props.setLocalCacheEnabled(false);
        HestaCacheService cache = new HestaCacheService(props, new ObjectMapper(), null);

        assertThat(cache.isReadThroughCacheEnabled()).isFalse();
        cache.put("devices:home", "h1:ALL", List.of("item"), Duration.ofSeconds(20));
        assertThat(cache.get("devices:home", "h1:ALL", List.class)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictInsideTransaction_evictsImmediatelyAndAgainAfterCommit() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);

        Map<String, String> store = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        when(template.unlink(anyString())).thenAnswer(inv -> store.remove(inv.<String>getArgument(0)) != null);

        HestaRedisProperties props = new HestaRedisProperties();
        props.setEnabled(true);
        HestaCacheService cache = new HestaCacheService(props, new ObjectMapper(), template);

        cache.put("devices:home", "h1:ALL", "fresh", Duration.ofSeconds(20));
        assertThat(store).containsKey("hesta:devices:home:h1:ALL");

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evict("devices:home", "h1:ALL");
            assertThat(store).doesNotContainKey("hesta:devices:home:h1:ALL");

            // Simulate concurrent reader repopulating stale data before transaction commit
            cache.put("devices:home", "h1:ALL", "stale-pre-commit", Duration.ofSeconds(20));
            assertThat(cache.get("devices:home", "h1:ALL", String.class)).contains("stale-pre-commit");

            for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            assertThat(store).doesNotContainKey("hesta:devices:home:h1:ALL");
            assertThat(cache.get("devices:home", "h1:ALL", String.class)).isEmpty();
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void jwtAuthenticationFilter_rejectsDisabledAndLockedUsers() throws Exception {
        com.hesta.backend.security.JwtTokenProvider tokenProvider =
                mock(com.hesta.backend.security.JwtTokenProvider.class);
        com.hesta.backend.security.CustomUserDetailsService userDetailsService =
                mock(com.hesta.backend.security.CustomUserDetailsService.class);
        com.hesta.backend.security.JwtAuthenticationFilter filter =
                new com.hesta.backend.security.JwtAuthenticationFilter(tokenProvider, userDetailsService);

        UUID disabledId = UUID.randomUUID();
        UUID lockedId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();

        when(tokenProvider.validateToken(anyString())).thenReturn(true);
        when(tokenProvider.getUserIdFromToken("tok-disabled")).thenReturn(disabledId);
        when(tokenProvider.getUserIdFromToken("tok-locked")).thenReturn(lockedId);
        when(tokenProvider.getUserIdFromToken("tok-active")).thenReturn(activeId);

        var roleAuth = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        when(userDetailsService.loadUserById(disabledId)).thenReturn(new com.hesta.backend.security.CustomUserDetails(
                disabledId, "dis@hesta.homes", "", com.hesta.backend.enums.AccountStatus.DISABLED, roleAuth));
        when(userDetailsService.loadUserById(lockedId)).thenReturn(new com.hesta.backend.security.CustomUserDetails(
                lockedId, "lock@hesta.homes", "", com.hesta.backend.enums.AccountStatus.LOCKED, roleAuth));
        when(userDetailsService.loadUserById(activeId)).thenReturn(new com.hesta.backend.security.CustomUserDetails(
                activeId, "act@hesta.homes", "", com.hesta.backend.enums.AccountStatus.ACTIVE, roleAuth));

        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        try {
            for (String token : List.of("tok-disabled", "tok-locked")) {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                var req = new org.springframework.mock.web.MockHttpServletRequest();
                req.addHeader("Authorization", "Bearer " + token);
                filter.doFilter(req, new org.springframework.mock.web.MockHttpServletResponse(), chain);
                assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication())
                        .as("Authentication must be null for %s", token)
                        .isNull();
            }

            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            var activeReq = new org.springframework.mock.web.MockHttpServletRequest();
            activeReq.addHeader("Authorization", "Bearer tok-active");
            filter.doFilter(activeReq, new org.springframework.mock.web.MockHttpServletResponse(), chain);
            assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication())
                    .isNotNull();
            verify(chain, times(3)).doFilter(any(), any());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
