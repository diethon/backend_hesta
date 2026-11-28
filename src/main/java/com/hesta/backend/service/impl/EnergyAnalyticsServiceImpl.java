package com.hesta.backend.service.impl;

import com.hesta.backend.cache.HestaCacheService;
import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.dto.response.*;
import com.hesta.backend.energy.EnergyPowerCalculator;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceEnergyRollup;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.repository.DeviceEnergyRollupRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.service.EnergyAnalyticsService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnergyAnalyticsServiceImpl implements EnergyAnalyticsService {

    private final DeviceRepository deviceRepository;
    private final DeviceStateHistoryRepository historyRepository;
    private final HomeAuthorizationService homeAuthorizationService;

    @Autowired(required = false)
    private DeviceEnergyRollupRepository rollupRepository;

    @Autowired(required = false)
    private HestaCacheService cacheService;

    void setRollupRepository(DeviceEnergyRollupRepository rollupRepository) {
        this.rollupRepository = rollupRepository;
    }

    void setCacheService(HestaCacheService cacheService) {
        this.cacheService = cacheService;
    }

    // EVN 6-tier residential tariff (VNĐ/kWh)
    private static final double TIER_1_LIMIT = 50.0;
    private static final double TIER_2_LIMIT = 100.0;
    private static final double TIER_3_LIMIT = 200.0;
    private static final double TIER_4_LIMIT = 300.0;
    private static final double TIER_5_LIMIT = 400.0;

    private static final long PRICE_TIER_1 = 1893;
    private static final long PRICE_TIER_2 = 1956;
    private static final long PRICE_TIER_3 = 2271;
    private static final long PRICE_TIER_4 = 2860;
    private static final long PRICE_TIER_5 = 3197;
    private static final long PRICE_TIER_6 = 3302;

    private static final String HOME_ANALYTICS_NAMESPACE = "energy:home";
    private static final String DEVICE_ANALYTICS_NAMESPACE = "energy:device";

    // Fast Cache TTL (30 seconds)
    private static final long CACHE_TTL_MILLIS = 30_000L;

    private record CacheEntry<T>(T value, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final Map<String, CacheEntry<EnergyAnalyticsResponse>> homeAnalyticsCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<DeviceEnergyBreakdown>> deviceAnalyticsCache = new ConcurrentHashMap<>();

    @Override
    public void evictHomeAndDeviceCache(UUID homeId, UUID deviceId) {
        if (homeId != null) {
            String homePrefix = homeId.toString() + ":";
            homeAnalyticsCache.keySet().removeIf(k -> k.startsWith(homePrefix));
            if (cacheService != null) {
                cacheService.evictByPrefix(HOME_ANALYTICS_NAMESPACE, homePrefix);
            }
        }
        if (deviceId != null) {
            String devicePrefix = deviceId.toString() + ":";
            deviceAnalyticsCache.keySet().removeIf(k -> k.startsWith(devicePrefix));
            if (cacheService != null) {
                cacheService.evictByPrefix(DEVICE_ANALYTICS_NAMESPACE, devicePrefix);
            }
        }
    }

    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void onDeviceStateChanged(DeviceStateChangedEvent event) {
        if (event == null) return;
        UUID homeId = event.homeId();
        UUID deviceId = (event.payload() != null) ? event.payload().deviceId() : null;
        evictHomeAndDeviceCache(homeId, deviceId);
    }

    @Override
    @Transactional(readOnly = true)
    public EnergyAnalyticsResponse getHomeEnergyAnalytics(UUID userId, UUID homeId, String period) {
        homeAuthorizationService.requireAccess(userId, homeId);

        MemberAccessContext ctx = homeAuthorizationService.loadAccessContext(homeId, userId);

        String normPeriod = period == null ? "30D" : period.trim().toUpperCase();
        String scopeKey = (ctx == null || ctx.isOwner() || ctx.scope() != AccessScope.CUSTOM)
                ? "ALL"
                : ("USER:" + (userId != null ? userId.toString() : "anon"));
        String cacheKey = (homeId != null ? homeId.toString() : "null") + ":" + normPeriod + ":" + scopeKey;

        if (cacheService != null) {
            if (cacheService.isReadThroughCacheEnabled()) {
                Optional<EnergyAnalyticsResponse> cachedOpt =
                        cacheService.get(HOME_ANALYTICS_NAMESPACE, cacheKey, EnergyAnalyticsResponse.class);
                if (cachedOpt.isPresent()) {
                    return cachedOpt.get();
                }
            }
        } else {
            CacheEntry<EnergyAnalyticsResponse> cached = homeAnalyticsCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                return cached.value();
            }
        }

        OffsetDateTime now = EnergyPowerCalculator.nowVn();
        OffsetDateTime from = resolveStartTime(now, normPeriod);
        OffsetDateTime currentHourStart = now.truncatedTo(ChronoUnit.HOURS);

        List<Device> devices = deviceRepository.findByHomeIdWithRoomAndNode(homeId).stream()
                .filter(d -> !d.isDeleted())
                .filter(d -> {
                    if (ctx != null && !ctx.isOwner() && ctx.scope() == AccessScope.CUSTOM) {
                        AccessLevel level = homeAuthorizationService.effectiveLevel(ctx, d);
                        return level != null && level.canView();
                    }
                    return true;
                })
                .toList();
        if (devices.isEmpty()) {
            EnergyAnalyticsResponse emptyResponse = EnergyAnalyticsResponse.builder()
                    .summary(EnergySummaryResponse.builder().period(normPeriod).build())
                    .timeSeries(Collections.emptyList())
                    .breakdown(Collections.emptyList())
                    .build();
            if (cacheService != null) {
                if (cacheService.isReadThroughCacheEnabled()) {
                    cacheService.put(HOME_ANALYTICS_NAMESPACE, cacheKey, emptyResponse, Duration.ofMillis(CACHE_TTL_MILLIS));
                }
            } else {
                homeAnalyticsCache.put(cacheKey, new CacheEntry<>(emptyResponse, System.currentTimeMillis() + CACHE_TTL_MILLIS));
            }
            return emptyResponse;
        }

        List<UUID> deviceIds = devices.stream().map(Device::getId).collect(Collectors.toList());
        OffsetDateTime liveTailFrom = from.isAfter(currentHourStart) ? from : currentHourStart;

        List<DeviceEnergyRollup> completedRollups = (rollupRepository != null && from.isBefore(currentHourStart))
                ? rollupRepository.findByDeviceIdInAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                        deviceIds, from, currentHourStart)
                : Collections.emptyList();
        Map<UUID, List<DeviceEnergyRollup>> rollupsByDevice = completedRollups.stream()
                .collect(Collectors.groupingBy(DeviceEnergyRollup::getDeviceId));

        List<DeviceStateHistory> allHistories = historyRepository.findByDeviceIdsAndChangedAtBetween(deviceIds, from, now);
        Map<UUID, List<DeviceStateHistory>> historyByDevice = allHistories.stream()
                .collect(Collectors.groupingBy(h -> h.getDevice().getId()));

        List<UUID> rollupDeviceIds = new ArrayList<>();
        List<UUID> rawDeviceIds = new ArrayList<>();
        for (Device device : devices) {
            List<DeviceEnergyRollup> devRollups = rollupsByDevice.getOrDefault(device.getId(), Collections.emptyList());
            if (!devRollups.isEmpty() && from.isBefore(currentHourStart)) {
                rollupDeviceIds.add(device.getId());
            } else {
                rawDeviceIds.add(device.getId());
            }
        }
        Map<UUID, DeviceStateHistory> rawLastBefore = loadLatestBeforeMap(rawDeviceIds, from);
        Map<UUID, DeviceStateHistory> tailLastBefore = loadLatestBeforeMap(rollupDeviceIds, liveTailFrom);

        double totalHomeKwh = 0.0;
        double currentHourHomeKwh = 0.0;
        int activeDeviceCount = 0;
        List<DeviceEnergyBreakdown> breakdownList = new ArrayList<>();
        Map<UUID, Double> rawDeviceKwh = new HashMap<>();
        List<ActiveEnergySegment> allHomeSegments = new ArrayList<>();

        for (Device device : devices) {
            List<DeviceStateHistory> deviceHistories = historyByDevice.getOrDefault(device.getId(), Collections.emptyList());
            List<DeviceEnergyRollup> devRollups = rollupsByDevice.getOrDefault(device.getId(), Collections.emptyList());

            DeviceEnergyResult devResult;
            double liveHourKwh = 0.0;
            if (!devRollups.isEmpty() && from.isBefore(currentHourStart)) {
                DeviceEnergyResult tailResult = calculateDeviceEnergy(device, deviceHistories, liveTailFrom, now, tailLastBefore);
                liveHourKwh = tailResult.kwh;
                devResult = mergeRollupsAndLiveTail(device, devRollups, tailResult);
            } else {
                devResult = calculateDeviceEnergy(device, deviceHistories, from, now, rawLastBefore);
                for (ActiveEnergySegment seg : devResult.segments) {
                    OffsetDateTime s = seg.start().isAfter(liveTailFrom) ? seg.start() : liveTailFrom;
                    OffsetDateTime e = seg.end().isBefore(now) ? seg.end() : now;
                    if (e.isAfter(s)) {
                        liveHourKwh += (seg.wattage() * Duration.between(s, e).toSeconds() / 3600.0) / 1000.0;
                    }
                }
            }

            totalHomeKwh += devResult.kwh;
            currentHourHomeKwh += liveHourKwh;
            allHomeSegments.addAll(devResult.segments);
            rawDeviceKwh.put(device.getId(), devResult.kwh);

            if (devResult.isOnNow || devResult.totalOnHours > 0.01) {
                activeDeviceCount++;
            }

            breakdownList.add(DeviceEnergyBreakdown.builder()
                    .deviceId(device.getId())
                    .deviceName(device.getName())
                    .deviceType(device.getDeviceType())
                    .roomName(device.getRoom() != null ? device.getRoom().getName() : "Chưa gán phòng")
                    .kwh(Math.round(devResult.kwh * 100.0) / 100.0)
                    .costVnd(0L)
                    .totalOnHours(Math.round(devResult.totalOnHours * 10.0) / 10.0)
                    .wattage(devResult.baseWattage)
                    .build());
        }

        // Calculate breakdown percentages & proportional progressive EVN cost (sum == estimatedCost)
        long estimatedCost = calculateEvnTariffCost(totalHomeKwh);
        final double finalTotal = Math.max(totalHomeKwh, 0.001);
        for (DeviceEnergyBreakdown item : breakdownList) {
            double devKwh = rawDeviceKwh.getOrDefault(item.getDeviceId(), 0.0);
            item.setPercentage(Math.round((item.getKwh() / finalTotal) * 1000.0) / 10.0);
            item.setCostVnd(totalHomeKwh > 0.0001 ? Math.round(estimatedCost * (devKwh / totalHomeKwh)) : 0L);
        }
        breakdownList.sort(Comparator.comparingDouble(DeviceEnergyBreakdown::getKwh).reversed());

        // Projected monthly consumption (extrapolated to full current calendar month)
        double daysInPeriod = Math.max(0.25, Duration.between(from, now).toSeconds() / 86400.0);
        int daysInCurrentMonth = now.toLocalDate().lengthOfMonth();
        double projectedMonthKwh = (totalHomeKwh / daysInPeriod) * daysInCurrentMonth;
        long projectedMonthCost = calculateEvnTariffCost(projectedMonthKwh);

        // Bậc EVN hiện tại phản ánh sản lượng tích lũy từ ngày 01 đầu tháng
        double tierBasisKwh = totalHomeKwh;
        if (("24H".equalsIgnoreCase(normPeriod) || "7D".equalsIgnoreCase(normPeriod)) && rollupRepository != null) {
            OffsetDateTime monthStart = now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            BigDecimal monthCompleted = rollupRepository.sumKwhByDeviceIdsAndWindow(deviceIds, monthStart, currentHourStart);
            double monthRollupKwh = monthCompleted != null ? monthCompleted.doubleValue() : 0.0;
            tierBasisKwh = Math.max(totalHomeKwh, monthRollupKwh + currentHourHomeKwh);
        }
        int currentTier = determineEvnTier(tierBasisKwh);

        Duration window = Duration.between(from, now);
        OffsetDateTime prevFrom = from.minus(window);
        List<DeviceEnergyRollup> prevRollups = rollupRepository != null
                ? rollupRepository.findByDeviceIdInAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                        deviceIds, prevFrom, from)
                : Collections.emptyList();
        Map<UUID, List<DeviceEnergyRollup>> prevRollupsByDevice = prevRollups.stream()
                .collect(Collectors.groupingBy(DeviceEnergyRollup::getDeviceId));

        List<UUID> prevFallbackDeviceIds = devices.stream()
                .map(Device::getId)
                .filter(id -> prevRollupsByDevice.getOrDefault(id, Collections.emptyList()).isEmpty())
                .toList();
        Map<UUID, List<DeviceStateHistory>> prevHistoryByDevice = Collections.emptyMap();
        Map<UUID, DeviceStateHistory> prevLastBefore = Collections.emptyMap();
        if (!prevFallbackDeviceIds.isEmpty()) {
            prevHistoryByDevice = historyRepository.findByDeviceIdsAndChangedAtBetween(prevFallbackDeviceIds, prevFrom, from)
                    .stream()
                    .collect(Collectors.groupingBy(h -> h.getDevice().getId()));
            prevLastBefore = loadLatestBeforeMap(prevFallbackDeviceIds, prevFrom);
        }
        double prevTotalHomeKwh = 0.0;
        for (Device device : devices) {
            List<DeviceEnergyRollup> devPrevRollups = prevRollupsByDevice.getOrDefault(device.getId(), Collections.emptyList());
            if (!devPrevRollups.isEmpty()) {
                for (DeviceEnergyRollup r : devPrevRollups) {
                    if (r.getKwh() != null) {
                        prevTotalHomeKwh += r.getKwh().doubleValue();
                    }
                }
            } else {
                List<DeviceStateHistory> devHistories = prevHistoryByDevice.getOrDefault(device.getId(), Collections.emptyList());
                prevTotalHomeKwh += calculateDeviceEnergy(device, devHistories, prevFrom, from, prevLastBefore).kwh;
            }
        }
        double compPercent = (prevTotalHomeKwh > 0.001)
                ? Math.round(((totalHomeKwh - prevTotalHomeKwh) / prevTotalHomeKwh) * 1000.0) / 10.0
                : 0.0;

        EnergySummaryResponse summary = EnergySummaryResponse.builder()
                .totalKwh(Math.round(totalHomeKwh * 100.0) / 100.0)
                .estimatedCostVnd(estimatedCost)
                .currentTier(currentTier)
                .projectedMonthKwh(Math.round(projectedMonthKwh * 100.0) / 100.0)
                .projectedMonthCostVnd(projectedMonthCost)
                .comparisonLastPeriodPercent(compPercent)
                .period(normPeriod)
                .activeDeviceCount(activeDeviceCount)
                .totalDeviceCount(devices.size())
                .build();

        List<EnergyTimeSeriesPoint> timeSeries = buildTimeSeries(normPeriod, from, now, allHomeSegments, totalHomeKwh, estimatedCost);

        EnergyAnalyticsResponse response = EnergyAnalyticsResponse.builder()
                .summary(summary)
                .timeSeries(timeSeries)
                .breakdown(breakdownList)
                .build();

        if (cacheService != null) {
            if (cacheService.isReadThroughCacheEnabled()) {
                cacheService.put(HOME_ANALYTICS_NAMESPACE, cacheKey, response, Duration.ofMillis(CACHE_TTL_MILLIS));
            }
        } else {
            homeAnalyticsCache.put(cacheKey, new CacheEntry<>(response, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        }
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceEnergyBreakdown getDeviceEnergy(UUID userId, UUID deviceId, String period) {
        Device device = deviceRepository.findById(deviceId)
                .filter(d -> !d.isDeleted())
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        UUID homeId = (device.getRoom() != null && device.getRoom().getHome() != null)
                ? device.getRoom().getHome().getId()
                : (device.getNode() != null && device.getNode().getHome() != null ? device.getNode().getHome().getId() : null);
        if (homeId != null) {
            homeAuthorizationService.requireAccess(userId, homeId);
        }
        homeAuthorizationService.requireDeviceAccess(userId, deviceId, AccessLevel.VIEW);

        String normPeriod = period == null ? "30D" : period.trim().toUpperCase();
        String cacheKey = (deviceId != null ? deviceId.toString() : "null") + ":" + normPeriod;

        if (cacheService != null) {
            if (cacheService.isReadThroughCacheEnabled()) {
                Optional<DeviceEnergyBreakdown> cachedOpt =
                        cacheService.get(DEVICE_ANALYTICS_NAMESPACE, cacheKey, DeviceEnergyBreakdown.class);
                if (cachedOpt.isPresent()) {
                    return cachedOpt.get();
                }
            }
        } else {
            CacheEntry<DeviceEnergyBreakdown> cached = deviceAnalyticsCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                return cached.value();
            }
        }

        OffsetDateTime now = EnergyPowerCalculator.nowVn();
        OffsetDateTime from = resolveStartTime(now, normPeriod);
        OffsetDateTime currentHourStart = now.truncatedTo(ChronoUnit.HOURS);

        List<DeviceStateHistory> histories = historyRepository
                .findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(deviceId, from, now);

        List<DeviceEnergyRollup> devRollups = (rollupRepository != null && from.isBefore(currentHourStart))
                ? rollupRepository.findByDeviceIdAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                        deviceId, from, currentHourStart)
                : Collections.emptyList();

        DeviceEnergyResult result;
        if (!devRollups.isEmpty() && from.isBefore(currentHourStart)) {
            DeviceEnergyResult tailResult = calculateDeviceEnergy(device, histories, currentHourStart, now);
            result = mergeRollupsAndLiveTail(device, devRollups, tailResult);
        } else {
            result = calculateDeviceEnergy(device, histories, from, now);
        }

        DeviceEnergyBreakdown breakdown = DeviceEnergyBreakdown.builder()
                .deviceId(device.getId())
                .deviceName(device.getName())
                .deviceType(device.getDeviceType())
                .roomName(device.getRoom() != null ? device.getRoom().getName() : "")
                .kwh(Math.round(result.kwh * 100.0) / 100.0)
                .percentage(100.0)
                .costVnd(calculateEvnTariffCost(result.kwh))
                .totalOnHours(Math.round(result.totalOnHours * 10.0) / 10.0)
                .wattage(result.baseWattage)
                .build();

        if (cacheService != null) {
            if (cacheService.isReadThroughCacheEnabled()) {
                cacheService.put(DEVICE_ANALYTICS_NAMESPACE, cacheKey, breakdown, Duration.ofMillis(CACHE_TTL_MILLIS));
            }
        } else {
            deviceAnalyticsCache.put(cacheKey, new CacheEntry<>(breakdown, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        }
        return breakdown;
    }

    @Override
    public long calculateEvnTariffCost(double kwh) {
        if (kwh <= 0.0) return 0;

        double remaining = kwh;
        double cost = 0;

        // Tier 1: 0 - 50 kWh
        double t1 = Math.min(remaining, TIER_1_LIMIT);
        cost += t1 * PRICE_TIER_1;
        remaining -= t1;

        if (remaining > 0) {
            // Tier 2: 51 - 100 kWh (50 kWh)
            double t2 = Math.min(remaining, TIER_2_LIMIT - TIER_1_LIMIT);
            cost += t2 * PRICE_TIER_2;
            remaining -= t2;
        }

        if (remaining > 0) {
            // Tier 3: 101 - 200 kWh (100 kWh)
            double t3 = Math.min(remaining, TIER_3_LIMIT - TIER_2_LIMIT);
            cost += t3 * PRICE_TIER_3;
            remaining -= t3;
        }

        if (remaining > 0) {
            // Tier 4: 201 - 300 kWh (100 kWh)
            double t4 = Math.min(remaining, TIER_4_LIMIT - TIER_3_LIMIT);
            cost += t4 * PRICE_TIER_4;
            remaining -= t4;
        }

        if (remaining > 0) {
            // Tier 5: 301 - 400 kWh (100 kWh)
            double t5 = Math.min(remaining, TIER_5_LIMIT - TIER_4_LIMIT);
            cost += t5 * PRICE_TIER_5;
            remaining -= t5;
        }

        if (remaining > 0) {
            // Tier 6: > 400 kWh
            cost += remaining * PRICE_TIER_6;
        }

        // VAT 8%
        cost = cost * 1.08;

        return Math.round(cost);
    }

    @Override
    public int determineEvnTier(double kwh) {
        if (kwh <= TIER_1_LIMIT) return 1;
        if (kwh <= TIER_2_LIMIT) return 2;
        if (kwh <= TIER_3_LIMIT) return 3;
        if (kwh <= TIER_4_LIMIT) return 4;
        if (kwh <= TIER_5_LIMIT) return 5;
        return 6;
    }

    private OffsetDateTime resolveStartTime(OffsetDateTime now, String period) {
        return switch (period) {
            case "24H", "DAY", "DAILY" -> now.truncatedTo(ChronoUnit.HOURS).minusHours(23);
            case "7D", "WEEK", "WEEKLY" -> now.truncatedTo(ChronoUnit.DAYS).minusDays(6);
            case "30D", "MONTH", "MONTHLY" -> now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            default -> now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
        };
    }

    private record ActiveEnergySegment(OffsetDateTime start, OffsetDateTime end, double wattage) {}

    private record DeviceEnergyResult(
            double kwh,
            double totalOnHours,
            boolean isOnNow,
            int baseWattage,
            List<ActiveEnergySegment> segments
    ) {}

    private DeviceEnergyResult mergeRollupsAndLiveTail(
            Device device,
            List<DeviceEnergyRollup> devRollups,
            DeviceEnergyResult tailResult) {
        double totalKwh = tailResult.kwh;
        double totalOnHours = tailResult.totalOnHours;
        List<ActiveEnergySegment> segments = new ArrayList<>();
        for (DeviceEnergyRollup r : devRollups) {
            double bucketKwh = r.getKwh() != null ? r.getKwh().doubleValue() : 0.0;
            int onSec = r.getOnSeconds() != null ? r.getOnSeconds() : 0;
            totalKwh += bucketKwh;
            totalOnHours += onSec / 3600.0;
            if (bucketKwh > 0.0) {
                OffsetDateTime start = EnergyPowerCalculator.toVn(r.getBucketStart());
                segments.add(new ActiveEnergySegment(start, start.plusHours(1), bucketKwh * 1000.0));
            }
        }
        segments.addAll(tailResult.segments);
        return new DeviceEnergyResult(totalKwh, totalOnHours, tailResult.isOnNow, tailResult.baseWattage, segments);
    }

    private Map<UUID, DeviceStateHistory> loadLatestBeforeMap(List<UUID> deviceIds, OffsetDateTime beforeTime) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<DeviceStateHistory> list = historyRepository.findLatestBeforeByDeviceIds(deviceIds, beforeTime);
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<UUID, DeviceStateHistory> result = new HashMap<>();
        for (DeviceStateHistory h : list) {
            if (h.getDevice() != null && h.getDevice().getId() != null) {
                result.merge(h.getDevice().getId(), h, (a, b) ->
                        (a.getChangedAt() != null && b.getChangedAt() != null && b.getChangedAt().isAfter(a.getChangedAt())) ? b : a);
            }
        }
        return result;
    }

    /**
     * Thuật toán Tích Phân Số (Riemann Sum Integration) trên Timeline lịch sử trạng thái
     */
    private DeviceEnergyResult calculateDeviceEnergy(Device device, List<DeviceStateHistory> histories,
                                                     OffsetDateTime startTime, OffsetDateTime endTime) {
        return calculateDeviceEnergy(device, histories, startTime, endTime, null);
    }

    private DeviceEnergyResult calculateDeviceEnergy(Device device, List<DeviceStateHistory> histories,
                                                     OffsetDateTime startTime, OffsetDateTime endTime,
                                                     Map<UUID, DeviceStateHistory> preloadedLastBefore) {
        int baseWattage = EnergyPowerCalculator.resolveBaseWattage(device);
        boolean isOffline = device.getStatus() == DeviceStatus.OFFLINE
                || device.getStatus() == DeviceStatus.UNKNOWN;
        boolean isCurrentlyOn = !isOffline && EnergyPowerCalculator.isStateOn(device, device.getCurrentState());
        OffsetDateTime vnNow = EnergyPowerCalculator.nowVn();
        OffsetDateTime activeUntil = endTime.isAfter(vnNow) ? vnNow : endTime;
        if (isOffline) {
            OffsetDateTime lastSeen = EnergyPowerCalculator.toVn(device.getLastSeen());
            if (lastSeen != null && lastSeen.isBefore(activeUntil)) {
                activeUntil = lastSeen;
            }
        }

        if (!startTime.isBefore(activeUntil)) {
            return new DeviceEnergyResult(0.0, 0.0, isCurrentlyOn, baseWattage, Collections.emptyList());
        }

        if (histories.isEmpty()) {
            if (isCurrentlyOn) {
                Optional<DeviceStateHistory> lastBefore = preloadedLastBefore != null
                        ? Optional.ofNullable(preloadedLastBefore.get(device.getId()))
                        : historyRepository.findFirstByDeviceIdAndChangedAtLessThanOrderByChangedAtDesc(device.getId(), startTime);

                OffsetDateTime onSince = startTime;
                Map<String, Object> stateMap = device.getCurrentState();

                if (lastBefore.isPresent()) {
                    if (EnergyPowerCalculator.isStateOn(device, lastBefore.get().getNewState())) {
                        onSince = startTime;
                        stateMap = lastBefore.get().getNewState();
                    } else {
                        OffsetDateTime updatedVn = EnergyPowerCalculator.toVn(device.getUpdatedAt());
                        onSince = (updatedVn != null && updatedVn.isAfter(startTime))
                                ? updatedVn : startTime;
                    }
                } else {
                    OffsetDateTime createdVn = EnergyPowerCalculator.toVn(device.getCreatedAt());
                    if (createdVn != null && createdVn.isAfter(startTime)) {
                        onSince = createdVn;
                    }
                }

                if (onSince.isBefore(activeUntil)) {
                    double hours = Math.max(0.0, Duration.between(onSince, activeUntil).toSeconds() / 3600.0);
                    double effectiveWattage = EnergyPowerCalculator.resolveEffectiveWattage(device, baseWattage, stateMap);
                    double kwh = (effectiveWattage * hours) / 1000.0;
                    List<ActiveEnergySegment> segs = List.of(new ActiveEnergySegment(onSince, activeUntil, effectiveWattage));
                    return new DeviceEnergyResult(kwh, hours, true, baseWattage, segs);
                }
            }
            return new DeviceEnergyResult(0.0, 0.0, false, baseWattage, Collections.emptyList());
        }

        double totalHoursOn = 0.0;
        double totalKwh = 0.0;
        List<ActiveEnergySegment> segments = new ArrayList<>();

        OffsetDateTime cursorTime = startTime;
        Map<String, Object> currentActiveState;
        boolean currentStateIsOn;

        if (histories.get(0).getPreviousState() != null && !histories.get(0).getPreviousState().isEmpty()) {
            currentActiveState = histories.get(0).getPreviousState();
            currentStateIsOn = EnergyPowerCalculator.isStateOn(device, currentActiveState);
        } else {
            Optional<DeviceStateHistory> lastBefore = preloadedLastBefore != null
                    ? Optional.ofNullable(preloadedLastBefore.get(device.getId()))
                    : historyRepository.findFirstByDeviceIdAndChangedAtLessThanOrderByChangedAtDesc(device.getId(), startTime);
            if (lastBefore.isPresent() && lastBefore.get().getNewState() != null) {
                currentActiveState = lastBefore.get().getNewState();
                currentStateIsOn = EnergyPowerCalculator.isStateOn(device, currentActiveState);
            } else {
                currentActiveState = Collections.emptyMap();
                currentStateIsOn = false;
            }
        }

        for (DeviceStateHistory record : histories) {
            OffsetDateTime recordTime = EnergyPowerCalculator.toVn(record.getChangedAt());
            if (recordTime.isBefore(cursorTime)) {
                currentActiveState = record.getNewState();
                currentStateIsOn = EnergyPowerCalculator.isStateOn(device, currentActiveState);
                continue;
            }
            if (!recordTime.isBefore(activeUntil)) {
                break;
            }

            if (currentStateIsOn) {
                double segmentHours = Math.max(0.0, Duration.between(cursorTime, recordTime).toSeconds() / 3600.0);
                double effectiveWattage = EnergyPowerCalculator.resolveEffectiveWattage(device, baseWattage, currentActiveState);
                totalHoursOn += segmentHours;
                totalKwh += (effectiveWattage * segmentHours) / 1000.0;
                segments.add(new ActiveEnergySegment(cursorTime, recordTime, effectiveWattage));
            }

            cursorTime = recordTime;
            currentActiveState = record.getNewState();
            currentStateIsOn = EnergyPowerCalculator.isStateOn(device, currentActiveState);
        }

        // Đoạn cuối cùng từ bản ghi cuối đến thời điểm hiện tại (không cộng dồn vô hạn nếu thiết bị đang OFFLINE)
        if (currentStateIsOn && !isOffline && cursorTime.isBefore(activeUntil)) {
            double tailHours = Math.max(0.0, Duration.between(cursorTime, activeUntil).toSeconds() / 3600.0);
            double effectiveWattage = EnergyPowerCalculator.resolveEffectiveWattage(device, baseWattage, currentActiveState);
            totalHoursOn += tailHours;
            totalKwh += (effectiveWattage * tailHours) / 1000.0;
            segments.add(new ActiveEnergySegment(cursorTime, activeUntil, effectiveWattage));
        }

        return new DeviceEnergyResult(totalKwh, totalHoursOn, isCurrentlyOn, baseWattage, segments);
    }

    private List<EnergyTimeSeriesPoint> buildTimeSeries(
            String period, OffsetDateTime from, OffsetDateTime to, List<ActiveEnergySegment> allSegments,
            double totalHomeKwh, long estimatedCost) {
        List<EnergyTimeSeriesPoint> points = new ArrayList<>();
        int count;
        if ("24H".equalsIgnoreCase(period)) {
            count = 24;
        } else if ("7D".equalsIgnoreCase(period)) {
            count = 7;
        } else {
            // Hiển thị các ngày từ ngày 01 đầu tháng hiện tại đến ngày hôm nay
            count = Math.max(1, to.getDayOfMonth());
        }

        DateTimeFormatter hourFmt = DateTimeFormatter.ofPattern("HH:00");
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("dd/MM");

        // Calculate progressive effective rate (VNĐ/kWh) so chart costs are consistent with progressive monthly summary
        double effectiveRate = (totalHomeKwh > 0.001)
                ? ((double) estimatedCost / totalHomeKwh)
                : (PRICE_TIER_1 * 1.08);

        for (int i = 0; i < count; i++) {
            OffsetDateTime bucketStart;
            OffsetDateTime bucketEnd;
            String label;

            if ("24H".equalsIgnoreCase(period)) {
                bucketStart = from.plusHours(i);
                bucketEnd = bucketStart.plusHours(1);
                label = bucketStart.format(hourFmt);
            } else {
                bucketStart = from.plusDays(i);
                bucketEnd = bucketStart.plusDays(1);
                label = bucketStart.format(dayFmt);
            }

            double bucketKwh = 0.0;
            for (ActiveEnergySegment seg : allSegments) {
                OffsetDateTime segStart = seg.start().isAfter(bucketStart) ? seg.start() : bucketStart;
                OffsetDateTime segEnd = seg.end().isBefore(bucketEnd) ? seg.end() : bucketEnd;

                if (segEnd.isAfter(segStart)) {
                    double segHours = Duration.between(segStart, segEnd).toSeconds() / 3600.0;
                    bucketKwh += (seg.wattage() * segHours) / 1000.0;
                }
            }

            double ptKwh = Math.round(bucketKwh * 100.0) / 100.0;
            long ptCost = Math.round(ptKwh * effectiveRate);

            points.add(EnergyTimeSeriesPoint.builder()
                    .timestamp(bucketStart)
                    .label(label)
                    .kwh(ptKwh)
                    .costVnd(ptCost)
                    .build());
        }

        return points;
    }
}
