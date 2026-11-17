package com.hesta.backend.job;

import com.hesta.backend.config.AnomalyDetectionProperties;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceAnomaly;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.AnomalyType;
import com.hesta.backend.repository.DeviceAnomalyRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.SensorOfflineRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class SensorOfflineMonitorScheduler {

    private final DeviceRepository deviceRepository;
    private final DeviceAnomalyRepository deviceAnomalyRepository;
    private final AnomalyDetectionProperties properties;
    private final SensorOfflineRecordService recordService;

    private final Instant startupTime = Instant.now();

    @Scheduled(fixedDelayString = "${hesta.anomaly.offline-check-interval:60s}")
    public void monitorOfflineSensors() {
        if (!properties.isEnabled()) {
            return;
        }

        Duration uptime = Duration.between(startupTime, Instant.now());
        if (uptime.compareTo(properties.getOfflineGracePeriod()) < 0) {
            log.debug("SensorOfflineMonitorScheduler: startup grace period active (uptime {}s < grace {}s). Skipping scan.",
                    uptime.getSeconds(), properties.getOfflineGracePeriod().getSeconds());
            return;
        }

        try {
            executeScan();
        } catch (Exception e) {
            log.error("Error executing SensorOfflineMonitorScheduler scan", e);
        }
    }

    public void executeScan() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime offlineCutoff = now.minus(properties.getOfflineThreshold());
        OffsetDateTime maxAgeCutoff = now.minus(properties.getOfflineMaxAge());

        List<String> monitoredTypes = properties.getOfflineMonitoredTypes();
        if (monitoredTypes == null || monitoredTypes.isEmpty()) {
            return;
        }

        List<Device> candidates = deviceRepository.findCandidateSensorsForOfflineMonitoring(
                monitoredTypes, maxAgeCutoff);

        Map<UUID, Device> candidateMap = candidates.stream()
                .collect(Collectors.toMap(Device::getId, d -> d, (k1, k2) -> k1));

        // 1. Check each candidate: if lastSeen < offlineCutoff -> offline!
        for (Device device : candidates) {
            if (device.getLastSeen() != null && device.getLastSeen().isBefore(offlineCutoff)) {
                recordService.handleDeviceOffline(device, properties.getOfflineThreshold());
            }
        }

        // 2. Check all active SENSOR_OFFLINE anomalies: if device is alive again -> auto-resolve!
        List<DeviceAnomaly> activeOfflineAnomalies = deviceAnomalyRepository.findActiveByTypeWithDevice(
                AnomalyType.SENSOR_OFFLINE,
                List.of(AnomalyStatus.OPEN, AnomalyStatus.ACKNOWLEDGED)
        );

        for (DeviceAnomaly activeAnomaly : activeOfflineAnomalies) {
            Device device = activeAnomaly.getDevice();
            if (device == null) {
                continue;
            }

            if (device.isDeleted()) {
                recordService.handleDeviceRecovered(activeAnomaly, device.getLastSeen());
                continue;
            }

            Device latest = candidateMap.getOrDefault(device.getId(), device);
            if (latest.getLastSeen() != null && !latest.getLastSeen().isBefore(offlineCutoff)) {
                // Device has recovered and is actively sending readings
                recordService.handleDeviceRecovered(activeAnomaly, latest.getLastSeen());
            }
        }
    }
}
