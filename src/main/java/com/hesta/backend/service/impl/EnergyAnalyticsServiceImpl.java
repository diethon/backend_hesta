package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.*;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.service.EnergyAnalyticsService;
import com.hesta.backend.service.HomeAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnergyAnalyticsServiceImpl implements EnergyAnalyticsService {

    private final DeviceRepository deviceRepository;
    private final DeviceStateHistoryRepository historyRepository;
    private final HomeAuthorizationService homeAuthorizationService;

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

    @Override
    @Transactional(readOnly = true)
    public EnergyAnalyticsResponse getHomeEnergyAnalytics(UUID userId, UUID homeId, String period) {
        homeAuthorizationService.requireAccess(userId, homeId);

        String normPeriod = period == null ? "30D" : period.trim().toUpperCase();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime from = resolveStartTime(now, normPeriod);

        List<Device> devices = deviceRepository.findByHomeIdWithRoomAndNode(homeId);
        if (devices.isEmpty()) {
            return EnergyAnalyticsResponse.builder()
                    .summary(EnergySummaryResponse.builder().period(normPeriod).build())
                    .timeSeries(Collections.emptyList())
                    .breakdown(Collections.emptyList())
                    .build();
        }

        List<UUID> deviceIds = devices.stream().map(Device::getId).collect(Collectors.toList());
        List<DeviceStateHistory> allHistories = historyRepository.findByDeviceIdsAndChangedAtBetween(deviceIds, from, now);

        Map<UUID, List<DeviceStateHistory>> historyByDevice = allHistories.stream()
                .collect(Collectors.groupingBy(h -> h.getDevice().getId()));

        double totalHomeKwh = 0.0;
        int activeDeviceCount = 0;
        List<DeviceEnergyBreakdown> breakdownList = new ArrayList<>();
        List<ActiveEnergySegment> allHomeSegments = new ArrayList<>();

        for (Device device : devices) {
            List<DeviceStateHistory> deviceHistories = historyByDevice.getOrDefault(device.getId(), Collections.emptyList());
            DeviceEnergyResult devResult = calculateDeviceEnergy(device, deviceHistories, from, now);
            totalHomeKwh += devResult.kwh;
            allHomeSegments.addAll(devResult.segments);

            if (devResult.isOnNow || devResult.totalOnHours > 0.01) {
                activeDeviceCount++;
            }

            breakdownList.add(DeviceEnergyBreakdown.builder()
                    .deviceId(device.getId())
                    .deviceName(device.getName())
                    .deviceType(device.getDeviceType())
                    .roomName(device.getRoom() != null ? device.getRoom().getName() : "Chưa gán phòng")
                    .kwh(Math.round(devResult.kwh * 100.0) / 100.0)
                    .costVnd(calculateEvnTariffCost(devResult.kwh))
                    .totalOnHours(Math.round(devResult.totalOnHours * 10.0) / 10.0)
                    .wattage(devResult.baseWattage)
                    .build());
        }

        // Calculate breakdown percentages
        final double finalTotal = Math.max(totalHomeKwh, 0.001);
        for (DeviceEnergyBreakdown item : breakdownList) {
            item.setPercentage(Math.round((item.getKwh() / finalTotal) * 1000.0) / 10.0);
        }
        breakdownList.sort(Comparator.comparingDouble(DeviceEnergyBreakdown::getKwh).reversed());

        long estimatedCost = calculateEvnTariffCost(totalHomeKwh);

        // Projected monthly consumption (30 days extrapolation)
        double daysInPeriod = Math.max(1.0, Duration.between(from, now).toSeconds() / 86400.0);
        double projectedMonthKwh = (totalHomeKwh / daysInPeriod) * 30.0;
        long projectedMonthCost = calculateEvnTariffCost(projectedMonthKwh);

        // Bậc EVN sinh hoạt được quy chuẩn theo tháng (30 ngày)
        int currentTier = determineEvnTier("30D".equalsIgnoreCase(normPeriod) ? totalHomeKwh : projectedMonthKwh);

        Duration window = Duration.between(from, now);
        OffsetDateTime prevFrom = from.minus(window);
        List<DeviceStateHistory> prevHistories = historyRepository.findByDeviceIdsAndChangedAtBetween(deviceIds, prevFrom, from);
        Map<UUID, List<DeviceStateHistory>> prevHistoryByDevice = prevHistories.stream()
                .collect(Collectors.groupingBy(h -> h.getDevice().getId()));
        double prevTotalHomeKwh = 0.0;
        for (Device device : devices) {
            List<DeviceStateHistory> devHistories = prevHistoryByDevice.getOrDefault(device.getId(), Collections.emptyList());
            prevTotalHomeKwh += calculateDeviceEnergy(device, devHistories, prevFrom, from).kwh;
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

        return EnergyAnalyticsResponse.builder()
                .summary(summary)
                .timeSeries(timeSeries)
                .breakdown(breakdownList)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceEnergyBreakdown getDeviceEnergy(UUID userId, UUID deviceId, String period) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        if (device.getHome() != null) {
            homeAuthorizationService.requireAccess(userId, device.getHome().getId());
        } else {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }

        String normPeriod = period == null ? "30D" : period.trim().toUpperCase();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime from = resolveStartTime(now, normPeriod);

        List<DeviceStateHistory> histories = historyRepository
                .findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(deviceId, from, now);

        DeviceEnergyResult result = calculateDeviceEnergy(device, histories, from, now);

        return DeviceEnergyBreakdown.builder()
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
            case "24H", "DAY", "DAILY" -> now.minusHours(24);
            case "7D", "WEEK", "WEEKLY" -> now.minusDays(7);
            case "MONTH", "MONTHLY" -> now.minusDays(30);
            default -> now.minusDays(30);
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

    /**
     * Thuật toán Tích Phân Số (Riemann Sum Integration) trên Timeline lịch sử trạng thái
     */
    private DeviceEnergyResult calculateDeviceEnergy(Device device, List<DeviceStateHistory> histories,
                                                     OffsetDateTime startTime, OffsetDateTime endTime) {
        int baseWattage = resolveDeviceWattage(device);
        boolean isCurrentlyOn = isStateOn(device.getCurrentState());
        OffsetDateTime activeUntil = endTime.isAfter(OffsetDateTime.now()) ? OffsetDateTime.now() : endTime;

        if (histories.isEmpty()) {
            if (isCurrentlyOn) {
                // Kiểm tra trạng thái gần nhất trước startTime
                Optional<DeviceStateHistory> lastBefore = historyRepository
                        .findFirstByDeviceIdAndChangedAtLessThanOrderByChangedAtDesc(device.getId(), startTime);

                OffsetDateTime onSince = startTime;
                Map<String, Object> stateMap = device.getCurrentState();

                if (lastBefore.isPresent()) {
                    if (isStateOn(lastBefore.get().getNewState())) {
                        onSince = startTime; // Đã duy trì BẬT từ trước khoảng thời gian đang xét
                        stateMap = lastBefore.get().getNewState();
                    } else {
                        // Trước đó OFF, nhưng hiện tại ON -> Bắt đầu tính từ thời điểm cập nhật gần nhất
                        onSince = (device.getUpdatedAt() != null && device.getUpdatedAt().isAfter(startTime))
                                ? device.getUpdatedAt() : startTime;
                    }
                } else if (device.getCreatedAt() != null && device.getCreatedAt().isAfter(startTime)) {
                    onSince = device.getCreatedAt();
                }

                if (onSince.isBefore(activeUntil)) {
                    double hours = Math.max(0.0, Duration.between(onSince, activeUntil).toSeconds() / 3600.0);
                    double effectiveWattage = resolveEffectiveWattage(device, baseWattage, stateMap);
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
            currentStateIsOn = isStateOn(currentActiveState);
        } else {
            Optional<DeviceStateHistory> lastBefore = historyRepository
                    .findFirstByDeviceIdAndChangedAtLessThanOrderByChangedAtDesc(device.getId(), startTime);
            if (lastBefore.isPresent() && lastBefore.get().getNewState() != null) {
                currentActiveState = lastBefore.get().getNewState();
                currentStateIsOn = isStateOn(currentActiveState);
            } else {
                currentActiveState = Collections.emptyMap();
                currentStateIsOn = false;
            }
        }

        for (DeviceStateHistory record : histories) {
            OffsetDateTime recordTime = record.getChangedAt();
            if (recordTime.isBefore(cursorTime)) {
                currentActiveState = record.getNewState();
                currentStateIsOn = isStateOn(currentActiveState);
                continue;
            }

            if (currentStateIsOn) {
                double segmentHours = Math.max(0.0, Duration.between(cursorTime, recordTime).toSeconds() / 3600.0);
                double effectiveWattage = resolveEffectiveWattage(device, baseWattage, currentActiveState);
                totalHoursOn += segmentHours;
                totalKwh += (effectiveWattage * segmentHours) / 1000.0;
                segments.add(new ActiveEnergySegment(cursorTime, recordTime, effectiveWattage));
            }

            cursorTime = recordTime;
            currentActiveState = record.getNewState();
            currentStateIsOn = isStateOn(currentActiveState);
        }

        // Đoạn cuối cùng từ bản ghi cuối đến thời điểm hiện tại
        if (currentStateIsOn && cursorTime.isBefore(activeUntil)) {
            double tailHours = Math.max(0.0, Duration.between(cursorTime, activeUntil).toSeconds() / 3600.0);
            double effectiveWattage = resolveEffectiveWattage(device, baseWattage, currentActiveState);
            totalHoursOn += tailHours;
            totalKwh += (effectiveWattage * tailHours) / 1000.0;
            segments.add(new ActiveEnergySegment(cursorTime, activeUntil, effectiveWattage));
        }

        return new DeviceEnergyResult(totalKwh, totalHoursOn, isCurrentlyOn, baseWattage, segments);
    }

    private double resolveEffectiveWattage(Device device, int baseWattage, Map<String, Object> state) {
        if (state == null || state.isEmpty()) return baseWattage;

        String type = device.getDeviceType() == null ? "" : device.getDeviceType().toUpperCase();
        boolean isDimmer = type.contains("LIGHT") || type.contains("LED");

        // 1. Đèn Dimmer / RGB: tính theo % độ sáng thực tế (brightness: 1-100)
        if (isDimmer && state.get("brightness") != null) {
            try {
                double brightness = Double.parseDouble(state.get("brightness").toString().trim());
                if (brightness > 0) {
                    double factor = Math.min(Math.max(brightness, 1.0), 100.0) / 100.0;
                    return Math.round(baseWattage * factor * 10.0) / 10.0;
                }
            } catch (NumberFormatException ignored) {}
        }

        // 2. Quạt có cấp tốc độ (speed: 1=50%, 2=75%, 3=100%)
        if (type.contains("FAN") && state.get("speed") != null) {
            try {
                int speed = Integer.parseInt(state.get("speed").toString().trim());
                if (speed == 1) return baseWattage * 0.5;
                if (speed == 2) return baseWattage * 0.75;
                if (speed >= 3) return baseWattage * 1.0;
            } catch (NumberFormatException ignored) {}
        }

        return baseWattage;
    }

    private boolean isStateOn(Map<String, Object> state) {
        if (state == null || state.isEmpty()) return false;
        Object power = state.get("power");
        if (power != null) {
            String pStr = power.toString().trim();
            if ("ON".equalsIgnoreCase(pStr) || "TRUE".equalsIgnoreCase(pStr) || "1".equals(pStr)) {
                return true;
            }
        }
        Object st = state.get("state");
        if (st != null) {
            String sStr = st.toString().trim();
            return "OPENING".equalsIgnoreCase(sStr) || "OPEN".equalsIgnoreCase(sStr) || "RUNNING".equalsIgnoreCase(sStr);
        }
        return false;
    }

    private int resolveDeviceWattage(Device device) {
        if (device == null) return 60;

        // 1. Ưu tiên cấu hình công suất tùy chỉnh trong settings (nếu người dùng đã đặt)
        if (device.getSettings() != null && device.getSettings().get("wattage") != null) {
            try {
                int customW = Integer.parseInt(device.getSettings().get("wattage").toString().trim());
                if (customW > 0) return customW;
            } catch (NumberFormatException ignored) {}
        }

        if (device.getDeviceType() == null) return 60;
        String type = device.getDeviceType().toUpperCase();

        return switch (type) {
            case "AIR_CONDITIONER", "AC"        -> 1500;
            case "LIGHT", "LED", "LED_RGB"      -> 40;
            case "FAN"                          -> 65;
            case "GATE", "ROLLING_DOOR"         -> 350;
            case "SMART_PLUG", "SOCKET"         -> 500;
            case "HEATER", "WATER_HEATER"       -> 2500;
            case "TEMP_HUMID_SENSOR", "SENSOR"  -> 2;
            case "CAMERA_AI", "CAMERA"          -> 15;
            default                             -> 60;
        };
    }

    private List<EnergyTimeSeriesPoint> buildTimeSeries(
            String period, OffsetDateTime from, OffsetDateTime to, List<ActiveEnergySegment> allSegments,
            double totalHomeKwh, long estimatedCost) {
        List<EnergyTimeSeriesPoint> points = new ArrayList<>();
        int count = "24H".equalsIgnoreCase(period) ? 24 : "7D".equalsIgnoreCase(period) ? 7 : 30;

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
