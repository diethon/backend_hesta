package com.hesta.backend.realtime.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.dto.command.SensorReadingUpdatedEvent;
import com.hesta.backend.dto.response.TwinDeviceSnapshotResponse;
import com.hesta.backend.dto.response.TwinSensorSnapshotResponse;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.support.TwinFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(DeviceSensorRealtimeTransactionTest.TestConfig.class)
class DeviceSensorRealtimeTransactionTest {
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean RealtimeEventPublisher publisher;
    private final TwinFixtures fixture = new TwinFixtures();
    private final TwinSnapshotMapper mapper = new TwinSnapshotMapper(new ObjectMapper());

    @BeforeEach
    void resetPublisher() {
        reset(publisher);
    }

    @Test
    void deviceChange_onCommit_publishesExactlyOneDeviceWithCorrectContext() {
        var payload = mapper.device(fixture.light);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            events.publishEvent(new DeviceStateChangedEvent(fixture.home.getId(), payload));
            verifyNoInteractions(publisher);
        });
        var event = captured();
        assertThat(event.type()).isEqualTo(RealtimeEventType.DEVICE_STATE_CHANGED);
        assertThat(event.homeId()).isEqualTo(fixture.home.getId());
        assertThat(event.deviceId()).isEqualTo(fixture.light.getId());
        assertThat(event.data()).isEqualTo(payload).isInstanceOf(TwinDeviceSnapshotResponse.class);
        assertThat(payload.roomId()).isEqualTo(fixture.living.getId());
        assertThat(payload.currentState().path("power").asText()).isEqualTo("ON");
        assertThat(payload.lastSeen()).isEqualTo(TwinFixtures.TIME);
    }

    @Test
    void sensorChange_onCommit_publishesExactlyOneMetricWithCorrectContext() {
        var payload = mapper.sensor(fixture.readings.getFirst());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            events.publishEvent(new SensorReadingUpdatedEvent(fixture.home.getId(), payload));
            verifyNoInteractions(publisher);
        });
        var event = captured();
        assertThat(event.type()).isEqualTo(RealtimeEventType.SENSOR_READING_UPDATED);
        assertThat(event.homeId()).isEqualTo(fixture.home.getId());
        assertThat(event.deviceId()).isEqualTo(fixture.environment.getId());
        assertThat(event.data()).isEqualTo(payload).isInstanceOf(TwinSensorSnapshotResponse.class);
        assertThat(payload.sensorId()).isEqualTo(fixture.environment.getId() + ":TEMPERATURE");
        assertThat(payload.roomId()).isEqualTo(fixture.bedroom.getId());
        assertThat(payload.latestValue()).isEqualByComparingTo("26.400");
        assertThat(payload.unit()).isEqualTo("°C");
        assertThat(payload.observedAt()).isEqualTo(TwinFixtures.TIME);
    }

    @Test
    void changes_onRollbackOrWithoutTransaction_publishNothing() {
        var device = new DeviceStateChangedEvent(fixture.home.getId(), mapper.device(fixture.light));
        var sensor = new SensorReadingUpdatedEvent(fixture.home.getId(), mapper.sensor(fixture.readings.getFirst()));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            events.publishEvent(device);
            events.publishEvent(sensor);
            status.setRollbackOnly();
        });
        events.publishEvent(device);
        events.publishEvent(sensor);
        verifyNoInteractions(publisher);
    }

    private RealtimeEvent<?> captured() {
        ArgumentCaptor<RealtimeEvent<?>> captor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(publisher).publish(captor.capture());
        verifyNoMoreInteractions(publisher);
        assertThat(captor.getValue().eventId()).isNotBlank();
        assertThat(captor.getValue().timestamp()).isNotNull();
        return captor.getValue();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(DeviceSensorRealtimeListener.class)
    static class TestConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new NotificationRealtimeTransactionTest.TestTransactionManager();
        }
    }
}
