package com.hesta.backend.service;

import com.hesta.backend.support.TwinHealthTestSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.command.SensorReadingInput;
import com.hesta.backend.dto.command.SensorReadingUpdatedEvent;
import com.hesta.backend.entity.SensorReading;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import com.hesta.backend.service.impl.SensorReadingIngestionServiceImpl;
import com.hesta.backend.support.TwinFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SensorReadingIngestionServiceTest {
    @Mock DeviceRepository devices;
    @Mock SensorReadingRepository readings;
    @Mock HomeAuthorizationService authorization;
    @Mock ApplicationEventPublisher events;
    private final TwinFixtures fixture = new TwinFixtures();
    private final UUID userId = TwinFixtures.id(500);
    private SensorReadingIngestionService service;

    @BeforeEach
    void setUp() {
        service = new SensorReadingIngestionServiceImpl(devices, readings, authorization,
                TwinHealthTestSupport.mapper(new ObjectMapper()), events);
    }

    @Test
    void ingest_latest_persistsExactMetricAndAuthoritativeContext() {
        var input = input(" Temperature ", "29.4", TwinFixtures.TIME);
        var saved = stored(input, 200);
        when(devices.findForSensorReading(input.deviceId())).thenReturn(Optional.of(fixture.environment));
        when(readings.save(any())).thenReturn(saved);
        when(readings.findFirstByDeviceIdAndMetricTypeOrderByRecordedAtDescIdDesc(input.deviceId(), input.metricType()))
                .thenReturn(Optional.of(saved));

        var accepted = service.ingest(userId, input);

        assertThat(accepted.latest()).isTrue();
        assertThat(accepted.readingId()).isEqualTo(200L);
        assertThat(accepted.reading().sensorId()).isEqualTo(input.deviceId() + ": Temperature ");
        assertThat(accepted.reading().roomId()).isEqualTo(fixture.bedroom.getId());
        var entity = ArgumentCaptor.forClass(SensorReading.class);
        verify(readings).save(entity.capture());
        assertThat(entity.getValue().getMetricType()).isEqualTo(input.metricType());
        assertThat(entity.getValue().getRecordedAt()).isEqualTo(input.observedAt());
        assertThat(entity.getValue().getValue()).isEqualByComparingTo("29.4");
        verify(authorization).requireAccess(userId, fixture.home.getId());
        verify(events).publishEvent(new SensorReadingUpdatedEvent(fixture.home.getId(), accepted.reading()));
    }

    @Test
    void ingest_historical_persistsWithoutPublishing() {
        var input = input("TEMPERATURE", "28", TwinFixtures.TIME.minusMinutes(5));
        when(devices.findForSensorReading(input.deviceId())).thenReturn(Optional.of(fixture.environment));
        when(readings.save(any())).thenReturn(stored(input, 200));
        when(readings.findFirstByDeviceIdAndMetricTypeOrderByRecordedAtDescIdDesc(input.deviceId(), input.metricType()))
                .thenReturn(Optional.of(fixture.readings.getFirst()));
        assertThat(service.ingest(userId, input).latest()).isFalse();
        verify(readings).save(any());
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9999999.999", "-9999999.999", "29.4000", "0", "1E+6"})
    void ingest_representableNumericValues_doesNotInventPhysicalLimits(String value) {
        var input = input("CUSTOM", value, TwinFixtures.TIME);
        var saved = stored(input, 200);
        when(devices.findForSensorReading(input.deviceId())).thenReturn(Optional.of(fixture.environment));
        when(readings.save(any())).thenReturn(saved);
        when(readings.findFirstByDeviceIdAndMetricTypeOrderByRecordedAtDescIdDesc(input.deviceId(), input.metricType()))
                .thenReturn(Optional.of(saved));
        assertThat(service.ingest(userId, input).reading().latestValue()).isEqualByComparingTo(value);
    }

    @Test
    void ingest_withoutAuthenticatedCaller_rejectsBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.ingest(null, input("TEMPERATURE", "29", TwinFixtures.TIME)))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        verifyNoInteractions(devices, readings, events);
    }

    @Test
    void ingest_unknownOrDeletedDevice_rejectsBeforePersistence() {
        var input = input("TEMPERATURE", "29", TwinFixtures.TIME);
        when(devices.findForSensorReading(input.deviceId())).thenReturn(Optional.empty());
        assertRejected(input, ErrorCode.DEVICE_NOT_FOUND);
        fixture.environment.setDeleted(true);
        when(devices.findForSensorReading(input.deviceId())).thenReturn(Optional.of(fixture.environment));
        assertRejected(input, ErrorCode.DEVICE_NOT_FOUND);
        verifyNoInteractions(readings, events);
    }

    @Test
    void ingest_invalidHomeOrNonMember_rejectsBeforePersistence() {
        var input = input("TEMPERATURE", "29", TwinFixtures.TIME);
        when(devices.findForSensorReading(input.deviceId())).thenReturn(Optional.of(fixture.environment));
        when(authorization.requireAccess(userId, fixture.home.getId())).thenThrow(new AppException(ErrorCode.UNAUTHORIZED));
        assertRejected(input, ErrorCode.UNAUTHORIZED);
        fixture.environment.setHome(null);
        assertRejected(input, ErrorCode.HOME_NOT_FOUND);
        verifyNoInteractions(readings, events);
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void ingest_invalidInput_rejectsWithoutDatabaseOrEvents(InvalidInput invalid) {
        assertRejected(invalid.input(), invalid.code());
        verifyNoInteractions(devices, readings, events);
    }

    static Stream<InvalidInput> invalidInputs() {
        UUID id = TwinFixtures.id(22);
        OffsetDateTime time = TwinFixtures.TIME;
        return Stream.of(
                new InvalidInput(null, ErrorCode.INVALID_REQUEST),
                invalid(null, "T", BigDecimal.ONE, null, time, ErrorCode.SENSOR_DEVICE_REQUIRED),
                invalid(id, null, BigDecimal.ONE, null, time, ErrorCode.SENSOR_METRIC_INVALID),
                invalid(id, " ", BigDecimal.ONE, null, time, ErrorCode.SENSOR_METRIC_INVALID),
                invalid(id, "x".repeat(51), BigDecimal.ONE, null, time, ErrorCode.SENSOR_METRIC_INVALID),
                invalid(id, "T\0", BigDecimal.ONE, null, time, ErrorCode.SENSOR_METRIC_INVALID),
                invalid(id, "T", null, null, time, ErrorCode.SENSOR_VALUE_INVALID),
                invalid(id, "T", new BigDecimal("10000000"), null, time, ErrorCode.SENSOR_VALUE_INVALID),
                invalid(id, "T", new BigDecimal("-10000000"), null, time, ErrorCode.SENSOR_VALUE_INVALID),
                invalid(id, "T", new BigDecimal("0.0001"), null, time, ErrorCode.SENSOR_VALUE_INVALID),
                invalid(id, "T", BigDecimal.ONE, "x".repeat(21), time, ErrorCode.SENSOR_UNIT_INVALID),
                invalid(id, "T", BigDecimal.ONE, "\0", time, ErrorCode.SENSOR_UNIT_INVALID),
                invalid(id, "T", BigDecimal.ONE, null, null, ErrorCode.SENSOR_TIME_INVALID),
                invalid(id, "T", BigDecimal.ONE, null, time.plusNanos(1), ErrorCode.SENSOR_TIME_INVALID));
    }

    private static InvalidInput invalid(UUID id, String metric, BigDecimal value, String unit, OffsetDateTime time, ErrorCode code) {
        return new InvalidInput(new SensorReadingInput(id, metric, value, unit, time), code);
    }

    private void assertRejected(SensorReadingInput input, ErrorCode code) {
        assertThatThrownBy(() -> service.ingest(userId, input)).isInstanceOfSatisfying(AppException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private SensorReadingInput input(String metric, String value, OffsetDateTime time) {
        return new SensorReadingInput(fixture.environment.getId(), metric, new BigDecimal(value), "°C", time);
    }

    private SensorReading stored(SensorReadingInput input, long id) {
        return SensorReading.builder().id(id).device(fixture.environment).metricType(input.metricType())
                .value(input.value()).unit(input.unit()).recordedAt(input.observedAt()).build();
    }

    record InvalidInput(SensorReadingInput input, ErrorCode code) { }
}
