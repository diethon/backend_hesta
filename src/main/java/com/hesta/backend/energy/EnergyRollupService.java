package com.hesta.backend.energy;

import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceEnergyRollup;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.repository.DeviceEnergyRollupRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.service.EnergyAnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnergyRollupService {

    private static final long TELEMETRY_DEFAULT_INTERVAL_SEC = 60L;
    private static final long TELEMETRY_MIN_INTERVAL_SEC = 1L;
    private static final long TELEMETRY_MAX_INTERVAL_SEC = 900L;

    private final DeviceEnergyRollupRepository rollupRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceStateHistoryRepository historyRepository;

    @Autowired(required = false)
    @Lazy
    private EnergyAnalyticsService energyAnalyticsService;

    private final Map<UUID, OffsetDateTime> lastPowerTelemetryAt = new ConcurrentHashMap<>();

    @Transactional
    public void recordTelemetryReading(Device device, String metricType, BigDecimal value, String unit, OffsetDateTime recordedAt) {
        if (device == null || metricType == null || value == null || recordedAt == null) {
            return;
        }
        String metric = metricType.trim().toUpperCase(Locale.ROOT);
        if (!isPowerMetric(metric)) {
            return;
        }
        UUID homeId = resolveHomeId(device);
        if (homeId == null) {
            return;
        }

        double watts = value.doubleValue();
        if (watts < 0) {
            return;
        }
        if ("KW".equalsIgnoreCase(unit != null ? unit.trim() : "")) {
            watts = watts * 1000.0;
        }

        OffsetDateTime vnTime = EnergyPowerCalculator.toVn(recordedAt);
        OffsetDateTime prevTime = lastPowerTelemetryAt.get(device.getId());
        long deltaSec = TELEMETRY_DEFAULT_INTERVAL_SEC;
        if (prevTime != null) {
            long between = Duration.between(prevTime, vnTime).getSeconds();
            if (between <= 0) {
                deltaSec = TELEMETRY_MIN_INTERVAL_SEC;
            } else {
                deltaSec = Math.max(TELEMETRY_MIN_INTERVAL_SEC, Math.min(TELEMETRY_MAX_INTERVAL_SEC, between));
            }
        }
        lastPowerTelemetryAt.put(device.getId(), vnTime);

        double deltaKwh = (watts * deltaSec) / 3_600_000.0;
        OffsetDateTime bucketStart = vnTime.truncatedTo(ChronoUnit.HOURS);

        DeviceEnergyRollup rollup = rollupRepository.findByDeviceIdAndBucketStart(device.getId(), bucketStart)
                .orElseGet(() -> DeviceEnergyRollup.builder()
                        .homeId(homeId)
                        .deviceId(device.getId())
                        .bucketStart(bucketStart)
                        .kwh(BigDecimal.ZERO)
                        .onSeconds(0)
                        .effectiveWatts(BigDecimal.ZERO)
                        .source("TELEMETRY")
                        .updatedAt(vnTime)
                        .build());

        BigDecimal baseKwh = "TELEMETRY".equalsIgnoreCase(rollup.getSource()) && rollup.getKwh() != null
                ? rollup.getKwh()
                : BigDecimal.ZERO;
        int prevOnSec = "TELEMETRY".equalsIgnoreCase(rollup.getSource()) && rollup.getOnSeconds() != null
                ? rollup.getOnSeconds()
                : 0;

        int nextOnSec = watts > 0.5 ? Math.min(3600, prevOnSec + (int) deltaSec) : prevOnSec;
        rollup.setKwh(baseKwh.add(BigDecimal.valueOf(deltaKwh)).setScale(4, RoundingMode.HALF_UP));
        rollup.setOnSeconds(nextOnSec);
        rollup.setEffectiveWatts(BigDecimal.valueOf(watts).setScale(2, RoundingMode.HALF_UP));
        rollup.setSource("TELEMETRY");
        rollup.setUpdatedAt(EnergyPowerCalculator.nowVn());
        rollupRepository.save(rollup);

        if (energyAnalyticsService != null) {
            energyAnalyticsService.evictHomeAndDeviceCache(homeId, device.getId());
        }
    }

    @Transactional
    public void materializeDeviceCompletedHours(Device device, OffsetDateTime fromHour, OffsetDateTime currentHourStart) {
        if (device == null || device.isDeleted() || fromHour == null || currentHourStart == null
                || !fromHour.isBefore(currentHourStart)) {
            return;
        }
        UUID homeId = resolveHomeId(device);
        if (homeId == null) {
            return;
        }

        OffsetDateTime vnFrom = EnergyPowerCalculator.toVn(fromHour).truncatedTo(ChronoUnit.HOURS);
        OffsetDateTime vnEnd = EnergyPowerCalculator.toVn(currentHourStart).truncatedTo(ChronoUnit.HOURS);
        if (!vnFrom.isBefore(vnEnd)) {
            return;
        }

        List<DeviceEnergyRollup> existing = rollupRepository
                .findByDeviceIdAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                        device.getId(), vnFrom, vnEnd);
        Map<OffsetDateTime, DeviceEnergyRollup> existingByHour = new HashMap<>();
        for (DeviceEnergyRollup row : existing) {
            existingByHour.put(EnergyPowerCalculator.toVn(row.getBucketStart()).truncatedTo(ChronoUnit.HOURS), row);
        }

        List<DeviceStateHistory> histories = historyRepository
                .findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(device.getId(), vnFrom, vnEnd);

        Map<String, Object> activeState = resolveInitialState(device, histories, vnFrom);
        boolean activeIsOn = EnergyPowerCalculator.isStateOn(device, activeState);
        int baseWattage = EnergyPowerCalculator.resolveBaseWattage(device);

        List<Segment> segments = new ArrayList<>();
        OffsetDateTime cursor = vnFrom;
        for (DeviceStateHistory h : histories) {
            OffsetDateTime t = EnergyPowerCalculator.toVn(h.getChangedAt());
            if (t.isBefore(cursor)) {
                activeState = h.getNewState();
                activeIsOn = EnergyPowerCalculator.isStateOn(device, activeState);
                continue;
            }
            if (activeIsOn && t.isAfter(cursor)) {
                segments.add(new Segment(cursor, t, EnergyPowerCalculator.resolveEffectiveWattage(device, baseWattage, activeState)));
            }
            cursor = t;
            activeState = h.getNewState();
            activeIsOn = EnergyPowerCalculator.isStateOn(device, activeState);
        }

        boolean isOffline = device.getStatus() == DeviceStatus.OFFLINE || device.getStatus() == DeviceStatus.UNKNOWN;
        OffsetDateTime tailEnd = vnEnd;
        if (isOffline && device.getLastSeen() != null) {
            OffsetDateTime lastSeenVn = EnergyPowerCalculator.toVn(device.getLastSeen());
            if (lastSeenVn.isBefore(tailEnd)) {
                tailEnd = lastSeenVn;
            }
        }
        if (activeIsOn && !isOffline && cursor.isBefore(tailEnd)) {
            segments.add(new Segment(cursor, tailEnd, EnergyPowerCalculator.resolveEffectiveWattage(device, baseWattage, activeState)));
        }

        OffsetDateTime protectCutoff = vnEnd.minusHours(2);
        OffsetDateTime updatedAt = EnergyPowerCalculator.nowVn();

        for (OffsetDateTime hour = vnFrom; hour.isBefore(vnEnd); hour = hour.plusHours(1)) {
            DeviceEnergyRollup prevRow = existingByHour.get(hour);
            if (prevRow != null) {
                // Never overwrite TELEMETRY measurements or historical buckets older than 2 hours
                if ("TELEMETRY".equalsIgnoreCase(prevRow.getSource()) || hour.isBefore(protectCutoff)) {
                    continue;
                }
            }

            OffsetDateTime hourEnd = hour.plusHours(1);
            int secOn = 0;
            double bucketKwh = 0.0;
            double weightedWattsSec = 0.0;

            for (Segment seg : segments) {
                OffsetDateTime s = seg.start().isAfter(hour) ? seg.start() : hour;
                OffsetDateTime e = seg.end().isBefore(hourEnd) ? seg.end() : hourEnd;
                if (e.isAfter(s)) {
                    long sec = Duration.between(s, e).getSeconds();
                    secOn += (int) sec;
                    bucketKwh += (seg.watts() * sec) / 3_600_000.0;
                    weightedWattsSec += seg.watts() * sec;
                }
            }

            if (secOn <= 0 && prevRow == null) {
                continue;
            }

            int clampedSec = Math.min(3600, Math.max(0, secOn));
            double avgWatts = clampedSec > 0 ? weightedWattsSec / clampedSec : baseWattage;
            DeviceEnergyRollup target = prevRow != null ? prevRow : DeviceEnergyRollup.builder()
                    .homeId(homeId)
                    .deviceId(device.getId())
                    .bucketStart(hour)
                    .source("ESTIMATED")
                    .build();

            target.setKwh(BigDecimal.valueOf(bucketKwh).setScale(4, RoundingMode.HALF_UP));
            target.setOnSeconds(clampedSec);
            target.setEffectiveWatts(BigDecimal.valueOf(avgWatts).setScale(2, RoundingMode.HALF_UP));
            target.setUpdatedAt(updatedAt);
            rollupRepository.save(target);
        }
    }

    @EventListener
    public void onDeviceStateChanged(DeviceStateChangedEvent event) {
        if (event == null || event.payload() == null || event.payload().deviceId() == null) {
            return;
        }
        try {
            Device device = deviceRepository.findByIdWithNode(event.payload().deviceId()).orElse(null);
            if (device == null || device.isDeleted()) {
                return;
            }
            OffsetDateTime currentHourStart = EnergyPowerCalculator.nowVn().truncatedTo(ChronoUnit.HOURS);
            materializeDeviceCompletedHours(device, currentHourStart.minusHours(48), currentHourStart);
        } catch (Exception ex) {
            log.debug("Deferred hourly rollup on state change for device {}: {}", event.payload().deviceId(), ex.getMessage());
        }
    }

    @Scheduled(cron = "10 0 * * * ?", zone = "Asia/Ho_Chi_Minh")
    public void rollupPreviousHourForAllActiveDevices() {
        OffsetDateTime currentHourStart = EnergyPowerCalculator.nowVn().truncatedTo(ChronoUnit.HOURS);
        OffsetDateTime fromHour = currentHourStart.minusHours(2);
        List<Device> devices = deviceRepository.findAll();
        for (Device dev : devices) {
            if (dev.isDeleted()) continue;
            try {
                materializeDeviceCompletedHours(dev, fromHour, currentHourStart);
            } catch (Exception ex) {
                log.warn("Hourly energy rollup failed for device {}: {}", dev.getId(), ex.getMessage());
            }
        }
    }

    private Map<String, Object> resolveInitialState(Device device, List<DeviceStateHistory> histories, OffsetDateTime from) {
        if (!histories.isEmpty()) {
            Map<String, Object> prev = histories.get(0).getPreviousState();
            if (prev != null && !prev.isEmpty()) {
                return prev;
            }
        }
        Optional<DeviceStateHistory> lastBefore = historyRepository
                .findFirstByDeviceIdAndChangedAtLessThanOrderByChangedAtDesc(device.getId(), from);
        if (lastBefore.isPresent() && lastBefore.get().getNewState() != null) {
            return lastBefore.get().getNewState();
        }
        if (histories.isEmpty()) {
            return device.getCurrentState() != null ? device.getCurrentState() : Collections.emptyMap();
        }
        return Collections.emptyMap();
    }

    private static boolean isPowerMetric(String metric) {
        return "POWER".equals(metric) || "WATT".equals(metric)
                || "WATTS".equals(metric) || "ACTIVE_POWER".equals(metric);
    }

    private static UUID resolveHomeId(Device device) {
        if (device.getHome() != null) return device.getHome().getId();
        if (device.getRoom() != null && device.getRoom().getHome() != null) return device.getRoom().getHome().getId();
        if (device.getNode() != null && device.getNode().getHome() != null) return device.getNode().getHome().getId();
        return null;
    }

    private record Segment(OffsetDateTime start, OffsetDateTime end, double watts) {}
}
