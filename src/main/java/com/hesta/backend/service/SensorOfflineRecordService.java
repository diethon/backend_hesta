package com.hesta.backend.service;

import com.hesta.backend.dto.event.AnomalyRaisedEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceAnomaly;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.AnomalyType;
import com.hesta.backend.enums.SeverityLevel;
import com.hesta.backend.repository.DeviceAnomalyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SensorOfflineRecordService {

    private final DeviceAnomalyRepository deviceAnomalyRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleDeviceOffline(Device device, Duration offlineThresholdDuration) {
        if (device.getHome() == null) {
            log.warn("Device {} has no home associated; cannot create SENSOR_OFFLINE anomaly", device.getId());
            return;
        }

        Optional<DeviceAnomaly> activeOpt = deviceAnomalyRepository.findByDeviceIdAndAnomalyTypeAndStatusIn(
                device.getId(),
                AnomalyType.SENSOR_OFFLINE,
                List.of(AnomalyStatus.OPEN, AnomalyStatus.ACKNOWLEDGED)
        );

        if (activeOpt.isPresent()) {
            DeviceAnomaly existing = activeOpt.get();
            existing.setLastSeenAt(device.getLastSeen());
            deviceAnomalyRepository.save(existing);
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        long minutesOffline = Duration.between(device.getLastSeen(), now).toMinutes();

        DeviceAnomaly anomaly = DeviceAnomaly.builder()
                .home(device.getHome())
                .device(device)
                .anomalyType(AnomalyType.SENSOR_OFFLINE)
                .metric("heartbeat")
                .currentValue("OFFLINE (" + minutesOffline + "m)")
                .expectedRange("last_seen <= " + offlineThresholdDuration.toMinutes() + "m")
                .severity(SeverityLevel.MEDIUM)
                .status(AnomalyStatus.OPEN)
                .source("RULE")
                .reason(String.format("Cảm biến %s không gửi tín hiệu quá %d phút (lần cuối: %s)",
                        device.getName() != null ? device.getName() : device.getId(),
                        offlineThresholdDuration.toMinutes(),
                        device.getLastSeen()))
                .lastSeenAt(device.getLastSeen())
                .detectedAt(now)
                .occurrenceCount(1)
                .build();

        try {
            DeviceAnomaly saved = deviceAnomalyRepository.saveAndFlush(anomaly);
            log.warn("Created SENSOR_OFFLINE anomaly {} for device {} (name={})",
                    saved.getId(), device.getId(), device.getName());

            eventPublisher.publishEvent(new AnomalyRaisedEvent(
                    saved.getId(),
                    device.getHome().getId(),
                    device.getId(),
                    device.getName(),
                    AnomalyType.SENSOR_OFFLINE,
                    SeverityLevel.MEDIUM,
                    "heartbeat",
                    saved.getCurrentValue(),
                    saved.getExpectedRange(),
                    false,
                    saved.getDetectedAt()
            ));
        } catch (DataIntegrityViolationException ex) {
            log.info("Concurrent instance already recorded SENSOR_OFFLINE anomaly for device {}", device.getId());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleDeviceRecovered(DeviceAnomaly anomaly, OffsetDateTime lastSeen) {
        if (anomaly.getStatus() == AnomalyStatus.RESOLVED) {
            return;
        }

        anomaly.setStatus(AnomalyStatus.RESOLVED);
        anomaly.setResolvedAt(OffsetDateTime.now());
        anomaly.setLastSeenAt(lastSeen);
        anomaly.setResolutionNote("Tự động khôi phục: cảm biến đã gửi lại dữ liệu (lastSeen: " + lastSeen + ")");

        deviceAnomalyRepository.save(anomaly);
        log.info("Auto-resolved SENSOR_OFFLINE anomaly {} for device {}", anomaly.getId(), anomaly.getDevice().getId());
    }
}
