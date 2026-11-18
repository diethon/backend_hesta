package com.hesta.backend.job;

import com.hesta.backend.config.AnomalyDetectionProperties;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceAnomaly;
import com.hesta.backend.entity.Home;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.AnomalyType;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.SeverityLevel;
import com.hesta.backend.repository.DeviceAnomalyRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.SensorOfflineRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SensorOfflineMonitorSchedulerTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceAnomalyRepository deviceAnomalyRepository;

    @Mock
    private SensorOfflineRecordService recordService;

    private AnomalyDetectionProperties properties;
    private SensorOfflineMonitorScheduler scheduler;

    private Home home;
    private Device silentSensor;
    private Device activeSensor;

    @BeforeEach
    void setUp() {
        properties = new AnomalyDetectionProperties();
        properties.setOfflineThreshold(Duration.ofMinutes(5));
        properties.setOfflineGracePeriod(Duration.ofMinutes(5));
        properties.setOfflineMaxAge(Duration.ofHours(24));
        properties.setOfflineMonitoredTypes(List.of("GAS_SENSOR", "SMOKE_SENSOR", "TEMP_SENSOR"));

        scheduler = new SensorOfflineMonitorScheduler(
                deviceRepository,
                deviceAnomalyRepository,
                properties,
                recordService
        );

        home = Home.builder().id(UUID.randomUUID()).name("Smart Home").build();

        silentSensor = Device.builder()
                .id(UUID.randomUUID())
                .name("Gas Sensor")
                .deviceType("GAS_SENSOR")
                .status(DeviceStatus.ONLINE)
                .isDeleted(false)
                .lastSeen(OffsetDateTime.now().minusMinutes(10)) // 10m ago > 5m threshold -> OFFLINE
                .build();
        silentSensor.setRoom(com.hesta.backend.entity.Room.builder().id(UUID.randomUUID()).home(home).build());

        activeSensor = Device.builder()
                .id(UUID.randomUUID())
                .name("Smoke Sensor")
                .deviceType("SMOKE_SENSOR")
                .status(DeviceStatus.ONLINE)
                .isDeleted(false)
                .lastSeen(OffsetDateTime.now().minusMinutes(1)) // 1m ago < 5m threshold -> ALIVE
                .build();
        activeSensor.setRoom(com.hesta.backend.entity.Room.builder().id(UUID.randomUUID()).home(home).build());
    }

    @Test
    @DisplayName("monitorOfflineSensors: within startup grace period skips executing scan")
    void monitorOfflineSensors_WithinGracePeriod_SkipsScan() {
        // Just initialized, uptime is < 5m grace period
        scheduler.monitorOfflineSensors();

        verify(deviceRepository, never()).findCandidateSensorsForOfflineMonitoring(any(), any());
        verify(recordService, never()).handleDeviceOffline(any(), any());
    }

    @Test
    @DisplayName("executeScan: detects silent sensor and delegates to short transaction record service")
    void executeScan_DeviceSilent_InvokesRecordService() {
        when(deviceRepository.findCandidateSensorsForOfflineMonitoring(any(), any()))
                .thenReturn(List.of(silentSensor));
        when(deviceAnomalyRepository.findActiveByTypeWithDevice(eq(AnomalyType.SENSOR_OFFLINE), any()))
                .thenReturn(Collections.emptyList());

        scheduler.executeScan();

        verify(recordService, times(1)).handleDeviceOffline(eq(silentSensor), eq(properties.getOfflineThreshold()));
        // Ensure Device.status is NEVER modified by the scheduler
        assertThat(silentSensor.getStatus()).isEqualTo(DeviceStatus.ONLINE);
    }

    @Test
    @DisplayName("executeScan: auto-resolves active SENSOR_OFFLINE anomaly when sensor is alive again")
    void executeScan_DeviceAliveAgain_AutoResolvesActiveAnomaly() {
        DeviceAnomaly activeAnomaly = DeviceAnomaly.builder()
                .id(UUID.randomUUID())
                .home(home)
                .device(activeSensor)
                .anomalyType(AnomalyType.SENSOR_OFFLINE)
                .severity(SeverityLevel.MEDIUM)
                .status(AnomalyStatus.OPEN)
                .build();

        when(deviceRepository.findCandidateSensorsForOfflineMonitoring(any(), any()))
                .thenReturn(List.of(activeSensor));
        when(deviceAnomalyRepository.findActiveByTypeWithDevice(eq(AnomalyType.SENSOR_OFFLINE), any()))
                .thenReturn(List.of(activeAnomaly));

        scheduler.executeScan();

        // activeSensor is NOT offline (lastSeen was 1m ago, threshold 5m), so handleDeviceOffline is NOT called
        verify(recordService, never()).handleDeviceOffline(eq(activeSensor), any());
        // Since activeSensor is alive, activeAnomaly is resolved!
        verify(recordService, times(1)).handleDeviceRecovered(eq(activeAnomaly), eq(activeSensor.getLastSeen()));
        assertThat(activeSensor.getStatus()).isEqualTo(DeviceStatus.ONLINE);
    }

    @Test
    @DisplayName("executeScan: auto-resolves active SENSOR_OFFLINE anomaly if device was deleted")
    void executeScan_DeviceDeleted_ResolvesActiveAnomaly() {
        Device deletedDevice = Device.builder()
                .id(UUID.randomUUID())
                .name("Deleted Sensor")
                .deviceType("GAS_SENSOR")
                .status(DeviceStatus.ONLINE)
                .isDeleted(true)
                .lastSeen(OffsetDateTime.now().minusHours(2))
                .build();

        DeviceAnomaly activeAnomaly = DeviceAnomaly.builder()
                .id(UUID.randomUUID())
                .home(home)
                .device(deletedDevice)
                .anomalyType(AnomalyType.SENSOR_OFFLINE)
                .severity(SeverityLevel.MEDIUM)
                .status(AnomalyStatus.OPEN)
                .build();

        when(deviceRepository.findCandidateSensorsForOfflineMonitoring(any(), any()))
                .thenReturn(Collections.emptyList());
        when(deviceAnomalyRepository.findActiveByTypeWithDevice(eq(AnomalyType.SENSOR_OFFLINE), any()))
                .thenReturn(List.of(activeAnomaly));

        scheduler.executeScan();

        verify(recordService, times(1)).handleDeviceRecovered(eq(activeAnomaly), eq(deletedDevice.getLastSeen()));
    }
}
