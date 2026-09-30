package com.hesta.backend.mapper;

import com.hesta.backend.support.TwinHealthTestSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.MockSensorReadingRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.support.TwinFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class MockSensorExamplesTest {
    @Autowired ObjectMapper mapper;

    @Test
    void mockRequest_andResultingEvent_matchPublishedExamples() throws Exception {
        var requestFile = Path.of("docs/examples/mock-sensor-request.json").toFile();
        var request = mapper.readValue(requestFile, MockSensorReadingRequest.class);
        assertThat(mapper.readTree(mapper.writeValueAsBytes(request))).isEqualTo(mapper.readTree(requestFile));
        var fixture = new TwinFixtures();
        assertThat(request.deviceId()).isEqualTo(fixture.environment.getId());
        var reading = TwinFixtures.reading(500, fixture.environment, request.metricType(),
                request.value().toPlainString(), request.unit(), request.observedAt());
        var event = new RealtimeEvent<>("example-mock-sensor-event", RealtimeEventType.SENSOR_READING_UPDATED,
                fixture.home.getId(), request.deviceId(), TwinHealthTestSupport.mapper(mapper).sensor(reading),
                Instant.parse("2026-09-17T09:00:01Z"));
        assertThat(mapper.readTree(mapper.writeValueAsBytes(event)))
                .isEqualTo(mapper.readTree(Path.of("docs/examples/mock-sensor-event.json").toFile()));
    }

    @Test
    void invalidNumericExample_matchesExistingErrorEnvelope() throws Exception {
        var request = mapper.readValue(Path.of("docs/examples/mock-sensor-invalid-request.json").toFile(),
                MockSensorReadingRequest.class);
        assertThat(request.value()).isEqualByComparingTo("10000000");
        var response = ApiResponse.<Void>builder().code(ErrorCode.SENSOR_VALUE_INVALID.getCode())
                .message(ErrorCode.SENSOR_VALUE_INVALID.getMessage()).build();
        assertThat(mapper.readTree(mapper.writeValueAsBytes(response)))
                .isEqualTo(mapper.readTree(Path.of("docs/examples/mock-sensor-invalid-response.json").toFile()));
    }
}
