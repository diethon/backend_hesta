package com.hesta.backend.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.TwinHomeSnapshotResponse;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.support.TwinFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class TwinContractSerializationTest {
    @Autowired ObjectMapper objectMapper;
    private final TwinFixtures fixture = new TwinFixtures();

    @Test
    void snapshot_serialization_matchesPublishedExampleExactly() throws Exception {
        var mapper = new TwinSnapshotMapper(objectMapper);
        var snapshot = mapper.home(fixture.home, fixture.rooms, fixture.devices, fixture.readings);
        assertExample("twin-snapshot.json", ApiResponse.<TwinHomeSnapshotResponse>builder().result(snapshot).build());
    }

    @Test
    void deviceEvent_serialization_matchesPublishedExampleAndContainsOnlyOneNode() throws Exception {
        var payload = new TwinSnapshotMapper(objectMapper).device(fixture.light);
        var event = new RealtimeEvent<>("example-device-event", RealtimeEventType.DEVICE_STATE_CHANGED,
                fixture.home.getId(), fixture.light.getId(), payload, TwinFixtures.TIME.toInstant());
        JsonNode json = assertExample("twin-device-event.json", event);
        assertThat(json.path("data").has("rooms")).isFalse();
        assertThat(json.path("deviceId")).isEqualTo(json.path("data").path("deviceId"));
    }

    @Test
    void sensorEvent_serialization_matchesPublishedExampleAndSnapshotNode() throws Exception {
        var mapper = new TwinSnapshotMapper(objectMapper);
        var payload = mapper.sensor(fixture.readings.getFirst());
        var event = new RealtimeEvent<>("example-sensor-event", RealtimeEventType.SENSOR_READING_UPDATED,
                fixture.home.getId(), fixture.environment.getId(), payload, TwinFixtures.TIME.toInstant());
        JsonNode json = assertExample("twin-sensor-event.json", event);
        var snapshot = mapper.home(fixture.home, fixture.rooms, fixture.devices, fixture.readings);
        JsonNode snapshotJson = objectMapper.readTree(objectMapper.writeValueAsBytes(snapshot));
        assertThat(json.path("data")).isEqualTo(snapshotJson.path("rooms").get(1).path("sensors").get(1));
        assertThat(json.path("data").has("rooms")).isFalse();
        assertThat(json.path("deviceId")).isEqualTo(json.path("data").path("deviceId"));
    }

    @Test
    void device_withoutRoomOrObservation_serializesExplicitNulls() throws Exception {
        fixture.light.setRoom(null);
        fixture.light.setLastSeen(null);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(new TwinSnapshotMapper(objectMapper)
                .device(fixture.light)));
        assertThat(json.has("roomId")).isTrue();
        assertThat(json.path("roomId").isNull()).isTrue();
        assertThat(json.has("lastSeen")).isTrue();
        assertThat(json.path("lastSeen").isNull()).isTrue();
    }

    private JsonNode assertExample(String name, Object value) throws Exception {
        JsonNode actual = objectMapper.readTree(objectMapper.writeValueAsBytes(value));
        JsonNode expected = objectMapper.readTree(Path.of("docs", "examples", name).toFile());
        assertThat(actual).isEqualTo(expected);
        return actual;
    }
}
