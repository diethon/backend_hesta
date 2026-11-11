package com.hesta.backend.service.impl;

import com.hesta.backend.config.AnomalyDetectionProperties;
import com.hesta.backend.dto.event.AnomalyRaisedEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceAnomaly;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.SensorReading;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.AnomalyType;
import com.hesta.backend.enums.SeverityLevel;
import com.hesta.backend.repository.DeviceAnomalyRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import com.hesta.backend.service.AnomalyDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionServiceImpl implements AnomalyDetectionService {

    private final AnomalyDetectionProperties properties;
    private final DeviceAnomalyRepository anomalyRepository;
    private final SensorReadingRepository readingRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * In-memory streak tracking and suppression map.
     * Key: (deviceId, anomalyType).
     * NOTE: State is volatile in-memory and resets upon server restart.
     */
    private final ConcurrentHashMap<String, TrackerState> trackers = new ConcurrentHashMap<>();

    private static class TrackerState {
        int consecutiveViolations = 0;
        int consecutiveClears = 0;
        OffsetDateTime lastNotificationAt = null;
        OffsetDateTime manualResolvedAt = null;
    }

    private String trackerKey(UUID deviceId, AnomalyType type) {
        return deviceId + ":" + type.name();
    }

    @Override
    public void recordManualResolution(UUID deviceId, AnomalyType anomalyType) {
        if (deviceId == null || anomalyType == null) return;
        String key = trackerKey(deviceId, anomalyType);
        TrackerState tracker = trackers.computeIfAbsent(key, k -> new TrackerState());
        synchronized (tracker) {
            tracker.manualResolvedAt = OffsetDateTime.now();
            tracker.consecutiveViolations = 0;
            tracker.consecutiveClears = 0;
        }
        log.info("Recorded manual resolution for device {} type {}. Suppress window active.", deviceId, anomalyType);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluateReading(Device device, String metric, BigDecimal value, OffsetDateTime timestamp, Long readingId) {
        if (!properties.isEnabled()) {
            return;
        }
        if (device == null || metric == null || value == null) {
            log.warn("Anomaly evaluation skipped: missing device, metric or value");
            return;
        }

        try {
            AnomalyType anomalyType = mapAnomalyType(metric);
            if (anomalyType == null) {
                return; // Non-safety metric, ignore
            }

            AnomalyDetectionProperties.MetricThresholdConfig config = properties.getConfig(metric);
            if (config == null || !config.isEnabled()) {
                return;
            }

            double numValue = value.doubleValue();
            if (Double.isNaN(numValue) || Double.isInfinite(numValue)) {
                log.warn("Anomaly evaluation skipped: non-finite reading value {} for device {}", value, device.getId());
                return;
            }

            // Validate against physical bounds
            if (config.getValidMin() != null && numValue < config.getValidMin()) {
                log.warn("Reading {} out of valid min range [{}..{}] for device {}", numValue, config.getValidMin(), config.getValidMax(), device.getId());
                return;
            }
            if (config.getValidMax() != null && numValue > config.getValidMax()) {
                log.warn("Reading {} out of valid max range [{}..{}] for device {}", numValue, config.getValidMin(), config.getValidMax(), device.getId());
                return;
            }

            OffsetDateTime now = timestamp != null ? timestamp : OffsetDateTime.now();
            String key = trackerKey(device.getId(), anomalyType);
            TrackerState tracker = trackers.computeIfAbsent(key, k -> new TrackerState());

            synchronized (tracker) {
                // 1. Check manual resolution suppression window
                if (tracker.manualResolvedAt != null) {
                    if (now.isBefore(tracker.manualResolvedAt.plus(config.getManualResolveSuppress()))) {
                        log.debug("Anomaly creation suppressed by manual resolution cooldown for device {} type {}", device.getId(), anomalyType);
                        return;
                    } else {
                        tracker.manualResolvedAt = null; // window expired
                    }
                }

                // 2. Evaluate violation levels
                boolean isCritical = config.getCritical() != null && numValue >= config.getCritical();
                boolean isWarn = config.getWarn() != null && numValue >= config.getWarn();

                if (isCritical || isWarn) {
                    handleViolation(device, anomalyType, metric, numValue, config, tracker, isCritical, now, readingId);
                } else {
                    handleSafeReading(device, anomalyType, numValue, config, tracker, now);
                }
            }

        } catch (Exception ex) {
            log.error("Unhandled error evaluating anomaly for device {} metric {}: {}", device.getId(), metric, ex.getMessage(), ex);
        }
    }

    private void handleViolation(
            Device device,
            AnomalyType anomalyType,
            String metric,
            double numValue,
            AnomalyDetectionProperties.MetricThresholdConfig config,
            TrackerState tracker,
            boolean isCritical,
            OffsetDateTime now,
            Long readingId
    ) {
        tracker.consecutiveClears = 0;
        tracker.consecutiveViolations++;

        SeverityLevel candidateSeverity = isCritical ? SeverityLevel.CRITICAL : SeverityLevel.HIGH;
        int requiredReadings = isCritical ? config.getConfirmReadingsCritical() : config.getConfirmReadingsHigh();

        Optional<DeviceAnomaly> activeOpt = anomalyRepository.findByDeviceIdAndAnomalyTypeAndStatusIn(
                device.getId(),
                anomalyType,
                List.of(AnomalyStatus.OPEN, AnomalyStatus.ACKNOWLEDGED)
        );

        if (activeOpt.isPresent()) {
            DeviceAnomaly active = activeOpt.get();
            active.setLastSeenAt(now);
            active.setOccurrenceCount(active.getOccurrenceCount() + 1);
            active.setCurrentValue(formatValue(numValue, config.getUnit()));

            boolean shouldAlert = false;
            boolean isEscalation = false;

            // Escalate HIGH -> CRITICAL
            if (active.getSeverity() == SeverityLevel.HIGH && candidateSeverity == SeverityLevel.CRITICAL) {
                active.setSeverity(SeverityLevel.CRITICAL);
                active.setStatus(AnomalyStatus.OPEN);
                active.setReason(buildReason(anomalyType, numValue, config, true));
                isEscalation = true;
                shouldAlert = true;
                log.warn("Escalating anomaly {} for device {} from HIGH to CRITICAL ({}{})",
                        active.getId(), device.getId(), numValue, config.getUnit());
            }

            anomalyRepository.save(active);

            if (shouldAlert) {
                tracker.lastNotificationAt = now;
                publishAlertEvent(active, device, isEscalation);
            }

        } else {
            // New anomaly: check streak requirement
            if (tracker.consecutiveViolations >= requiredReadings) {
                createAnomaly(device, anomalyType, metric, numValue, candidateSeverity, config, tracker, now, readingId);
            } else {
                log.info("Violation observed for device {} type {} ({}/{} required confirmations)",
                        device.getId(), anomalyType, tracker.consecutiveViolations, requiredReadings);
            }
        }
    }

    private void createAnomaly(
            Device device,
            AnomalyType anomalyType,
            String metric,
            double numValue,
            SeverityLevel severity,
            AnomalyDetectionProperties.MetricThresholdConfig config,
            TrackerState tracker,
            OffsetDateTime now,
            Long readingId
    ) {
        Home home = device.getHome();
        if (home == null) {
            log.warn("Cannot create anomaly: Device {} is not associated with any Home", device.getId());
            return;
        }

        SensorReading readingRef = null;
        if (readingId != null) {
            readingRef = readingRepository.findById(readingId).orElse(null);
        }

        DeviceAnomaly newAnomaly = DeviceAnomaly.builder()
                .home(home)
                .device(device)
                .anomalyType(anomalyType)
                .metric(metric)
                .currentValue(formatValue(numValue, config.getUnit()))
                .expectedRange(formatExpectedRange(config))
                .severity(severity)
                .status(AnomalyStatus.OPEN)
                .source("RULE")
                .reason(buildReason(anomalyType, numValue, config, false))
                .reading(readingRef)
                .detectedAt(now)
                .lastSeenAt(now)
                .occurrenceCount(1)
                .build();

        try {
            DeviceAnomaly saved = anomalyRepository.save(newAnomaly);
            tracker.lastNotificationAt = now;
            publishAlertEvent(saved, device, false);
            log.warn("Raised new {} anomaly [{}] for device {} at {}{}",
                    severity, anomalyType, device.getName(), numValue, config.getUnit());

        } catch (DataIntegrityViolationException ex) {
            // Anti-race protection: another thread committed active anomaly concurrently
            log.warn("Race condition on active anomaly insertion for device {} type {}. Updating existing anomaly.", device.getId(), anomalyType);
            Optional<DeviceAnomaly> concurrent = anomalyRepository.findByDeviceIdAndAnomalyTypeAndStatusIn(
                    device.getId(),
                    anomalyType,
                    List.of(AnomalyStatus.OPEN, AnomalyStatus.ACKNOWLEDGED)
            );
            concurrent.ifPresent(active -> {
                active.setLastSeenAt(now);
                active.setOccurrenceCount(active.getOccurrenceCount() + 1);
                active.setCurrentValue(formatValue(numValue, config.getUnit()));
                anomalyRepository.save(active);
            });
        }
    }

    private void handleSafeReading(
            Device device,
            AnomalyType anomalyType,
            double numValue,
            AnomalyDetectionProperties.MetricThresholdConfig config,
            TrackerState tracker,
            OffsetDateTime now
    ) {
        tracker.consecutiveViolations = 0;

        double warnLimit = config.getWarn() != null ? config.getWarn() : 0.0;
        double clearThreshold = warnLimit - (warnLimit * config.getClearMargin());

        if (numValue <= clearThreshold) {
            tracker.consecutiveClears++;
            if (tracker.consecutiveClears >= config.getClearReadings()) {
                Optional<DeviceAnomaly> activeOpt = anomalyRepository.findByDeviceIdAndAnomalyTypeAndStatusIn(
                        device.getId(),
                        anomalyType,
                        List.of(AnomalyStatus.OPEN, AnomalyStatus.ACKNOWLEDGED)
                );
                activeOpt.ifPresent(active -> {
                    active.setStatus(AnomalyStatus.RESOLVED);
                    active.setResolvedAt(now);
                    active.setResolutionNote(String.format(
                            "Tự động phục hồi theo cơ chế Hysteresis: giá trị an toàn (%.1f %s <= %.1f %s) duy trì qua %d lần đọc liên tiếp",
                            numValue, config.getUnit(), clearThreshold, config.getUnit(), tracker.consecutiveClears
                    ));
                    anomalyRepository.save(active);
                    log.info("Auto-resolved anomaly {} for device {} via hysteresis", active.getId(), device.getId());
                });
                tracker.consecutiveClears = 0;
            }
        } else {
            // Between clearThreshold and warn: in hysteresis zone, don't clear yet
            tracker.consecutiveClears = 0;
        }
    }

    private void publishAlertEvent(DeviceAnomaly anomaly, Device device, boolean isEscalated) {
        try {
            UUID homeId = anomaly.getHome() != null ? anomaly.getHome().getId() : (device.getHome() != null ? device.getHome().getId() : null);
            if (homeId == null) return;

            AnomalyRaisedEvent event = new AnomalyRaisedEvent(
                    anomaly.getId(),
                    homeId,
                    device.getId(),
                    device.getName(),
                    anomaly.getAnomalyType(),
                    anomaly.getSeverity(),
                    anomaly.getMetric(),
                    anomaly.getCurrentValue(),
                    anomaly.getExpectedRange(),
                    isEscalated,
                    anomaly.getDetectedAt()
            );
            eventPublisher.publishEvent(event);
            log.info("Published AnomalyRaisedEvent for anomaly {} in home {}", anomaly.getId(), homeId);
        } catch (Exception ex) {
            log.error("Failed to publish AnomalyRaisedEvent for anomaly {}: {}", anomaly.getId(), ex.getMessage());
        }
    }

    private AnomalyType mapAnomalyType(String metric) {
        String m = metric.trim().toLowerCase();
        if (m.equals("gas") || m.equals("gas_ppm")) return AnomalyType.GAS_LEAKAGE;
        if (m.equals("smoke")) return AnomalyType.SMOKE_DETECTED;
        if (m.equals("temperature") || m.equals("temp")) return AnomalyType.HIGH_TEMPERATURE;
        return null;
    }

    private String formatValue(double val, String unit) {
        return String.format("%.1f %s", val, unit != null ? unit : "").trim();
    }

    private String formatExpectedRange(AnomalyDetectionProperties.MetricThresholdConfig config) {
        if (config.getWarn() != null) {
            return String.format("< %.1f %s", config.getWarn(), config.getUnit()).trim();
        }
        return "< normal";
    }

    private String buildReason(AnomalyType type, double val, AnomalyDetectionProperties.MetricThresholdConfig config, boolean isEscalation) {
        String prefix = isEscalation ? "Mức độ nguy hiểm tăng cao: " : "Vượt ngưỡng an toàn: ";
        return switch (type) {
            case GAS_LEAKAGE -> String.format("%sNồng độ khí gas đo được %.1f %s (ngưỡng cảnh báo: %.1f %s, nguy hiểm: %.1f %s)",
                    prefix, val, config.getUnit(), config.getWarn(), config.getUnit(), config.getCritical(), config.getUnit());
            case SMOKE_DETECTED -> String.format("%sNồng độ khói đo được %.1f %s (ngưỡng cảnh báo: %.1f %s, nguy hiểm: %.1f %s)",
                    prefix, val, config.getUnit(), config.getWarn(), config.getUnit(), config.getCritical(), config.getUnit());
            case HIGH_TEMPERATURE -> String.format("%sNhiệt độ đo được %.1f %s (ngưỡng cảnh báo: %.1f %s, nguy hiểm: %.1f %s)",
                    prefix, val, config.getUnit(), config.getWarn(), config.getUnit(), config.getCritical(), config.getUnit());
            default -> String.format("%sGiá trị cảm biến đo được %.1f %s", prefix, val, config.getUnit());
        };
    }
}
