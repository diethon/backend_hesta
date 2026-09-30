package com.hesta.backend.service;

import com.hesta.backend.dto.response.TwinHealthStatusChangedPayload;
import com.hesta.backend.enums.TwinHealthStatus;
import com.hesta.backend.enums.TwinNodeType;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisher;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import com.hesta.backend.repository.TwinHealthReference;
import com.hesta.backend.service.impl.TwinHealthEvaluationServiceImpl;
import com.hesta.backend.support.MutableClock;
import com.hesta.backend.support.TwinFixtures;
import com.hesta.backend.support.TwinHealthTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwinHealthEvaluationServiceTest {
    @Mock DeviceRepository devices;
    @Mock SensorReadingRepository sensors;
    @Mock RealtimeEventPublisher publisher;
    @Mock PlatformTransactionManager transactions;
    private final MutableClock clock = new MutableClock(TwinFixtures.TIME.toInstant());
    private final List<TwinHealthReference> deviceRows = new ArrayList<>();
    private final List<TwinHealthReference> sensorRows = new ArrayList<>();
    private TwinHealthEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new TwinHealthEvaluationServiceImpl(devices, sensors, TwinHealthTestSupport.resolver(clock),
                clock, publisher, transactions);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(devices.findHealthReferences()).thenAnswer(call -> deviceRows);
        when(sensors.findHealthReferences()).thenAnswer(call -> sensorRows);
    }

    @Test
    void evaluate_timePasses_publishesEachTransitionOnceWithoutNewActivity() {
        sensorRows.add(row("TEMPERATURE", TwinFixtures.TIME));
        service.evaluateAll(); // silent baseline
        service.evaluateAll();
        verifyNoInteractions(publisher);
        clock.advance(Duration.ofSeconds(30));
        service.evaluateAll();
        service.evaluateAll();
        var stale = capture(1).getFirst();
        assertTransition(stale, TwinHealthStatus.ACTIVE, TwinHealthStatus.STALE, "TEMPERATURE");
        clock.advance(Duration.ofSeconds(270));
        service.evaluateAll();
        service.evaluateAll();
        var offline = capture(2).get(1);
        assertTransition(offline, TwinHealthStatus.STALE, TwinHealthStatus.OFFLINE, "TEMPERATURE");
    }

    @ParameterizedTest
    @ValueSource(longs = {30, 300})
    void committedActivity_staleOrOffline_recoversOnceUsingLatestDatabaseTime(long age) {
        sensorRows.add(row("TEMPERATURE", TwinFixtures.TIME.minusSeconds(age)));
        service.evaluateAll();
        when(sensors.findHealthReference(TwinFixtures.id(22), "TEMPERATURE"))
                .thenReturn(Optional.of(row("TEMPERATURE", TwinFixtures.TIME)));
        service.evaluateSensor(TwinFixtures.id(22), "TEMPERATURE");
        service.evaluateSensor(TwinFixtures.id(22), "TEMPERATURE");
        assertTransition(capture(1).getFirst(), age == 30 ? TwinHealthStatus.STALE : TwinHealthStatus.OFFLINE,
                TwinHealthStatus.ACTIVE, "TEMPERATURE");
    }

    @Test
    void evaluate_metricsAndDevice_haveIndependentHealthAndKeepAuthoritativeRouting() {
        deviceRows.add(row(null, null));
        sensorRows.add(row("TEMPERATURE", TwinFixtures.TIME));
        sensorRows.add(row("HUMIDITY", TwinFixtures.TIME.minusMinutes(5)));
        service.evaluateAll();
        clock.advance(Duration.ofSeconds(30));
        service.evaluateAll();
        assertTransition(capture(1).getFirst(), TwinHealthStatus.ACTIVE, TwinHealthStatus.STALE, "TEMPERATURE");
        when(devices.findHealthReference(TwinFixtures.id(22))).thenReturn(Optional.of(row(null,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))));
        service.evaluateDevice(TwinFixtures.id(22));
        var deviceEvent = capture(2).get(1);
        var payload = (TwinHealthStatusChangedPayload) deviceEvent.data();
        assertThat(payload.nodeType()).isEqualTo(TwinNodeType.DEVICE);
        assertThat(payload.nodeId()).isEqualTo(TwinFixtures.id(22).toString());
        assertThat(payload.previousStatus()).isEqualTo(TwinHealthStatus.OFFLINE);
        assertThat(payload.healthStatus()).isEqualTo(TwinHealthStatus.ACTIVE);
    }

    @Test
    void evaluate_deletedNode_isPrunedAndReappearanceEstablishesNewBaseline() {
        deviceRows.add(row(null, TwinFixtures.TIME));
        service.evaluateAll();
        deviceRows.clear();
        service.evaluateAll();
        deviceRows.add(row(null, null));
        service.evaluateAll();
        verifyNoInteractions(publisher);
    }

    @Test
    void evaluateSensor_delayedEventDoesNotUseItsOldTimestampOrBlindlyForceActive() {
        sensorRows.add(row("TEMPERATURE", TwinFixtures.TIME.minusMinutes(10)));
        service.evaluateAll();
        // Even a latest reading may already be stale at evaluation time.
        when(sensors.findHealthReference(TwinFixtures.id(22), "TEMPERATURE"))
                .thenReturn(Optional.of(row("TEMPERATURE", TwinFixtures.TIME.minusSeconds(40))));
        service.evaluateSensor(TwinFixtures.id(22), "TEMPERATURE");
        assertTransition(capture(1).getFirst(), TwinHealthStatus.OFFLINE, TwinHealthStatus.STALE, "TEMPERATURE");
    }

    private List<RealtimeEvent<?>> capture(int count) {
        ArgumentCaptor<RealtimeEvent<?>> events = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(publisher, times(count)).publish(events.capture());
        return events.getAllValues();
    }

    private void assertTransition(RealtimeEvent<?> event, TwinHealthStatus previous, TwinHealthStatus current, String metric) {
        assertThat(event.type()).isEqualTo(RealtimeEventType.TWIN_HEALTH_STATUS_CHANGED);
        assertThat(event.homeId()).isEqualTo(TwinFixtures.id(1));
        assertThat(event.deviceId()).isEqualTo(TwinFixtures.id(22));
        var payload = (TwinHealthStatusChangedPayload) event.data();
        assertThat(payload.nodeType()).isEqualTo(TwinNodeType.SENSOR);
        assertThat(payload.nodeId()).isEqualTo(TwinFixtures.id(22) + ":" + metric);
        assertThat(payload.deviceId()).isEqualTo(TwinFixtures.id(22));
        assertThat(payload.roomId()).isEqualTo(TwinFixtures.id(12));
        assertThat(payload.previousStatus()).isEqualTo(previous);
        assertThat(payload.healthStatus()).isEqualTo(current);
        assertThat(payload.evaluatedAt()).isEqualTo(clock.instant());
    }

    private TwinHealthReference row(String metric, OffsetDateTime time) {
        return new Reference(TwinFixtures.id(1), TwinFixtures.id(22), TwinFixtures.id(12), metric, time);
    }

    record Reference(UUID home, UUID device, UUID room, String metric, OffsetDateTime time) implements TwinHealthReference {
        @Override public UUID getHomeId() { return home; }
        @Override public UUID getDeviceId() { return device; }
        @Override public UUID getRoomId() { return room; }
        @Override public String getMetricType() { return metric; }
        @Override public OffsetDateTime getReferenceTime() { return time; }
    }
}
