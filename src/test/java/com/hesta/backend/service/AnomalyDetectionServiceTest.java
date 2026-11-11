package com.hesta.backend.service;

import com.hesta.backend.config.AnomalyDetectionProperties;
import com.hesta.backend.dto.event.AnomalyRaisedEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceAnomaly;
import com.hesta.backend.entity.Home;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.AnomalyType;
import com.hesta.backend.enums.SeverityLevel;
import com.hesta.backend.repository.DeviceAnomalyRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import com.hesta.backend.service.impl.AnomalyDetectionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnomalyDetectionServiceTest {

    @Mock
    private DeviceAnomalyRepository anomalyRepository;

    @Mock
    private SensorReadingRepository sensorReadingRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AnomalyDetectionProperties properties;
    private AnomalyDetectionServiceImpl service;

    private Device device;
    private Home home;
    private UUID deviceId;
    private UUID homeId;

    @BeforeEach
    void setUp() {
        properties = new AnomalyDetectionProperties();
        service = new AnomalyDetectionServiceImpl(
                properties,
                anomalyRepository,
                sensorReadingRepository,
                eventPublisher
        );

        deviceId = UUID.randomUUID();
        homeId = UUID.randomUUID();

        home = Home.builder().id(homeId).name("Home Sweet Home").build();
        device = Device.builder()
                .id(deviceId)
                .name("Gas Sensor Living Room")
                .deviceType("GAS_SENSOR")
                .isDeleted(false)
                .build();
        device.setRoom(com.hesta.backend.entity.Room.builder().id(UUID.randomUUID()).home(home).name("Living Room").build());
    }

    @Test
    @DisplayName("Readings below warn threshold should not raise an anomaly")
    void evaluateReading_BelowWarnThreshold_NoAnomalyRaised() {
        OffsetDateTime now = OffsetDateTime.now();

        // gas_ppm warn is 400.0. Reading 250.0 is normal.
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(250.0), now, 100L);

        verify(anomalyRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(AnomalyRaisedEvent.class));
    }

    @Test
    @DisplayName("Readings above warn threshold require configured confirmation streak before raising anomaly")
    void evaluateReading_ExceedsWarnThreshold_RequiresConsecutiveReadings() {
        OffsetDateTime now = OffsetDateTime.now();
        when(anomalyRepository.findByDeviceIdAndAnomalyTypeAndStatusIn(eq(deviceId), eq(AnomalyType.GAS_LEAKAGE), any()))
                .thenReturn(Optional.empty());
        when(anomalyRepository.save(any(DeviceAnomaly.class))).thenAnswer(invocation -> {
            DeviceAnomaly a = invocation.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        // Reading 1: gas_ppm = 500 (warn=400, confirmReadingsHigh=2). Streak = 1 -> no alert yet
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(500.0), now, 101L);
        verify(anomalyRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(AnomalyRaisedEvent.class));

        // Reading 2: gas_ppm = 520. Streak = 2 -> raises HIGH anomaly!
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(520.0), now.plusSeconds(5), 102L);

        ArgumentCaptor<DeviceAnomaly> captor = ArgumentCaptor.forClass(DeviceAnomaly.class);
        verify(anomalyRepository, times(1)).save(captor.capture());
        DeviceAnomaly created = captor.getValue();

        assertThat(created.getSeverity()).isEqualTo(SeverityLevel.HIGH);
        assertThat(created.getStatus()).isEqualTo(AnomalyStatus.OPEN);
        assertThat(created.getAnomalyType()).isEqualTo(AnomalyType.GAS_LEAKAGE);

        verify(eventPublisher, times(1)).publishEvent(any(AnomalyRaisedEvent.class));
    }

    @Test
    @DisplayName("Critical reading meeting streak requirement raises CRITICAL anomaly immediately")
    void evaluateReading_ExceedsCriticalThreshold_RaisesCritical() {
        OffsetDateTime now = OffsetDateTime.now();
        when(anomalyRepository.findByDeviceIdAndAnomalyTypeAndStatusIn(eq(deviceId), eq(AnomalyType.GAS_LEAKAGE), any()))
                .thenReturn(Optional.empty());
        when(anomalyRepository.save(any(DeviceAnomaly.class))).thenAnswer(invocation -> {
            DeviceAnomaly a = invocation.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        // Critical threshold is 1000.0, confirmReadingsCritical is 1
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(1200.0), now, 103L);

        ArgumentCaptor<DeviceAnomaly> captor = ArgumentCaptor.forClass(DeviceAnomaly.class);
        verify(anomalyRepository, times(1)).save(captor.capture());
        DeviceAnomaly created = captor.getValue();

        assertThat(created.getSeverity()).isEqualTo(SeverityLevel.CRITICAL);
        assertThat(created.getStatus()).isEqualTo(AnomalyStatus.OPEN);

        ArgumentCaptor<AnomalyRaisedEvent> eventCaptor = ArgumentCaptor.forClass(AnomalyRaisedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().severity()).isEqualTo(SeverityLevel.CRITICAL);
    }

    @Test
    @DisplayName("Escalation: active HIGH anomaly escalates to CRITICAL on dangerous reading")
    void evaluateReading_EscalatesFromHighToCritical() {
        OffsetDateTime now = OffsetDateTime.now();
        DeviceAnomaly existing = DeviceAnomaly.builder()
                .id(UUID.randomUUID())
                .home(home)
                .device(device)
                .anomalyType(AnomalyType.GAS_LEAKAGE)
                .severity(SeverityLevel.HIGH)
                .status(AnomalyStatus.ACKNOWLEDGED)
                .currentValue("500")
                .occurrenceCount(1)
                .build();

        when(anomalyRepository.findByDeviceIdAndAnomalyTypeAndStatusIn(eq(deviceId), eq(AnomalyType.GAS_LEAKAGE), any()))
                .thenReturn(Optional.of(existing));
        when(anomalyRepository.save(any(DeviceAnomaly.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Reading gas_ppm = 1500 (CRITICAL >= 1000)
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(1500.0), now, 104L);

        assertThat(existing.getSeverity()).isEqualTo(SeverityLevel.CRITICAL);
        assertThat(existing.getStatus()).isEqualTo(AnomalyStatus.OPEN); // resets to OPEN on escalation
        assertThat(existing.getOccurrenceCount()).isEqualTo(2);

        ArgumentCaptor<AnomalyRaisedEvent> eventCaptor = ArgumentCaptor.forClass(AnomalyRaisedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().isEscalated()).isTrue();
        assertThat(eventCaptor.getValue().severity()).isEqualTo(SeverityLevel.CRITICAL);
    }

    @Test
    @DisplayName("Hysteresis: active anomaly auto-resolves after N consecutive readings below clear margin")
    void evaluateReading_HysteresisAutoClear() {
        OffsetDateTime now = OffsetDateTime.now();
        DeviceAnomaly existing = DeviceAnomaly.builder()
                .id(UUID.randomUUID())
                .home(home)
                .device(device)
                .anomalyType(AnomalyType.GAS_LEAKAGE)
                .severity(SeverityLevel.HIGH)
                .status(AnomalyStatus.OPEN)
                .currentValue("500")
                .build();

        when(anomalyRepository.findByDeviceIdAndAnomalyTypeAndStatusIn(eq(deviceId), eq(AnomalyType.GAS_LEAKAGE), any()))
                .thenReturn(Optional.of(existing));

        // Warn is 400, clearMargin is 0.10 (400 * 0.90 = 360). Clear readings required = 3.
        // Reading 1: 300 < 360. Clear streak = 1 -> not resolved yet
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(300.0), now, 201L);
        assertThat(existing.getStatus()).isEqualTo(AnomalyStatus.OPEN);

        // Reading 2: 290 < 360. Clear streak = 2 -> not resolved yet
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(290.0), now.plusSeconds(5), 202L);
        assertThat(existing.getStatus()).isEqualTo(AnomalyStatus.OPEN);

        // Reading 3: 280 < 360. Clear streak = 3 -> auto-resolves!
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(280.0), now.plusSeconds(10), 203L);
        assertThat(existing.getStatus()).isEqualTo(AnomalyStatus.RESOLVED);
        assertThat(existing.getResolvedAt()).isNotNull();
        assertThat(existing.getResolutionNote()).contains("Hysteresis");
        verify(anomalyRepository, times(1)).save(existing);
    }

    @Test
    @DisplayName("Manual resolution suppresses new alerts during cooldown window")
    void evaluateReading_ManualResolution_SuppressesDuringCooldown() {
        OffsetDateTime now = OffsetDateTime.now();

        // Record manual resolution
        service.recordManualResolution(deviceId, AnomalyType.GAS_LEAKAGE);

        // Subsequent high reading within cooldown (10m) should be suppressed
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(600.0), now, 301L);

        verify(anomalyRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(AnomalyRaisedEvent.class));
    }

    @Test
    @DisplayName("Input validation: null, out-of-bounds, or deleted devices are safely ignored")
    void evaluateReading_ValidationFailures_SafelyHandled() {
        OffsetDateTime now = OffsetDateTime.now();

        // Null device
        service.evaluateReading(null, "gas_ppm", BigDecimal.valueOf(500), now, 1L);

        // Deleted device
        Device deleted = Device.builder().id(UUID.randomUUID()).isDeleted(true).build();
        service.evaluateReading(deleted, "gas_ppm", BigDecimal.valueOf(500), now, 2L);

        // Out of valid range (validMax is 10000)
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(99999), now, 3L);

        // Negative reading below validMin 0
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(-5), now, 4L);

        verify(anomalyRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(AnomalyRaisedEvent.class));
    }

    @Test
    @DisplayName("Concurrent insert race condition catching DataIntegrityViolationException safely")
    void evaluateReading_ConcurrentCreation_HandlesDataIntegrityViolation() {
        OffsetDateTime now = OffsetDateTime.now();
        when(anomalyRepository.findByDeviceIdAndAnomalyTypeAndStatusIn(eq(deviceId), eq(AnomalyType.GAS_LEAKAGE), any()))
                .thenReturn(Optional.empty());
        when(anomalyRepository.save(any(DeviceAnomaly.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        // Critical streak is 1 for gas_ppm >= 1000
        service.evaluateReading(device, "gas_ppm", BigDecimal.valueOf(1500.0), now, 401L);

        // Method finishes without throwing unhandled exception
        verify(anomalyRepository, times(1)).save(any());
        verify(eventPublisher, never()).publishEvent(any(AnomalyRaisedEvent.class));
    }
}
