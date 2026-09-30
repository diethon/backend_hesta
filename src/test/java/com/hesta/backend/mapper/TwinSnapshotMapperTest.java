package com.hesta.backend.mapper;

import com.hesta.backend.support.TwinHealthTestSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.support.TwinFixtures;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TwinSnapshotMapperTest {
    @Test
    void health_usesIndependentAuthoritativeTimesAndPreservesDeviceStatus() {
        var clock = new com.hesta.backend.support.MutableClock(TwinFixtures.TIME.toInstant());
        var mapper = new TwinSnapshotMapper(new ObjectMapper(), TwinHealthTestSupport.resolver(clock), clock);
        fixture.light.setStatus(com.hesta.backend.enums.DeviceStatus.ERROR);
        assertThat(mapper.device(fixture.light).healthStatus()).isEqualTo(com.hesta.backend.enums.TwinHealthStatus.OFFLINE);
        assertThat(mapper.device(fixture.light).status()).isEqualTo(com.hesta.backend.enums.DeviceStatus.ERROR);
        fixture.light.setLastSeen(null);
        assertThat(mapper.device(fixture.light).healthStatus()).isEqualTo(com.hesta.backend.enums.TwinHealthStatus.OFFLINE);
        assertThat(mapper.sensor(fixture.readings.getFirst()).healthStatus()).isEqualTo(com.hesta.backend.enums.TwinHealthStatus.ACTIVE);
        clock.advance(java.time.Duration.ofSeconds(30));
        assertThat(mapper.sensor(fixture.readings.getFirst()).healthStatus()).isEqualTo(com.hesta.backend.enums.TwinHealthStatus.STALE);
        clock.advance(java.time.Duration.ofSeconds(270));
        assertThat(mapper.sensor(fixture.readings.getFirst()).healthStatus()).isEqualTo(com.hesta.backend.enums.TwinHealthStatus.ACTIVE);
        assertThat(fixture.light.getLastSeen()).isNull();
        assertThat(fixture.light.getStatus()).isEqualTo(com.hesta.backend.enums.DeviceStatus.ERROR);
    }

    private final TwinFixtures fixture = new TwinFixtures();
    private final TwinSnapshotMapper mapper = TwinHealthTestSupport.mapper(new ObjectMapper());

    @Test
    void home_withRoomsDevicesAndMetrics_mapsCompleteSnapshot() {
        var result = mapper.home(fixture.home, fixture.rooms, fixture.devices, fixture.readings);

        assertThat(result.homeId()).isEqualTo(fixture.home.getId());
        assertThat(result.name()).isEqualTo("My Home");
        assertThat(result.rooms()).hasSize(2);
        var living = result.rooms().getFirst();
        assertThat(living.roomId()).isEqualTo(fixture.living.getId());
        assertThat(living.homeId()).isEqualTo(result.homeId());
        assertThat(living.name()).isEqualTo("Living Room");
        assertThat(living.icon()).isEqualTo("sofa");
        assertThat(living.devices()).hasSize(2);
        var light = living.devices().getFirst();
        assertThat(light.deviceId()).isEqualTo(fixture.light.getId());
        assertThat(light.roomId()).isEqualTo(living.roomId());
        assertThat(light.status()).isEqualTo(fixture.light.getStatus());
        assertThat(light.currentState().path("power").asText()).isEqualTo("ON");
        assertThat(light.currentState().path("brightness").asInt()).isEqualTo(80);
        assertThat(light.lastSeen()).isNull();
        var sensors = result.rooms().get(1).sensors();
        assertThat(sensors).hasSize(2);
        var temperature = sensors.stream().filter(s -> s.metricType().equals("TEMPERATURE")).findFirst().orElseThrow();
        assertThat(temperature.sensorId()).isEqualTo(fixture.environment.getId() + ":TEMPERATURE");
        assertThat(temperature.deviceId()).isEqualTo(fixture.environment.getId());
        assertThat(temperature.roomId()).isEqualTo(fixture.bedroom.getId());
        assertThat(temperature.latestValue()).isEqualByComparingTo("26.400");
        assertThat(temperature.unit()).isEqualTo("°C");
        assertThat(temperature.observedAt()).isEqualTo(TwinFixtures.TIME);
        assertThat(sensors).extracting(s -> s.sensorId()).doesNotHaveDuplicates();
        assertThat(result.unassignedDevices()).isEmpty();
        assertThat(result.unassignedSensors()).isEmpty();
    }

    @Test
    void home_withUnassignedDeviceAndEmptyRooms_preservesAllNodesAndUnknownTimes() {
        var device = fixture.device(99, null, "Unassigned", "SENSOR", Map.of());
        device.setLastSeen(null);
        var reading = TwinFixtures.reading(200, device, "CUSTOM", "0", null, TwinFixtures.TIME);
        var result = mapper.home(fixture.home, fixture.rooms, List.of(device), List.of(reading));

        assertThat(result.rooms()).allSatisfy(room -> {
            assertThat(room.devices()).isEmpty();
            assertThat(room.sensors()).isEmpty();
        });
        assertThat(result.unassignedDevices()).singleElement().satisfies(node -> {
            assertThat(node.roomId()).isNull();
            assertThat(node.lastSeen()).isNull();
            assertThat(node.currentState().isEmpty()).isTrue();
        });
        assertThat(result.unassignedSensors()).singleElement().satisfies(node -> {
            assertThat(node.roomId()).isNull();
            assertThat(node.unit()).isNull();
        });
        assertThat(mapper.home(fixture.home, List.of(), List.of(), List.of()).rooms()).isEmpty();
    }

    @Test
    void device_afterMapping_doesNotShareMutablePersistenceState() {
        var nested = new HashMap<String, Object>(Map.of("level", 3));
        fixture.light.setCurrentState(new HashMap<>(Map.of("settings", nested)));
        var snapshot = mapper.device(fixture.light);
        nested.put("level", 9);
        assertThat(snapshot.currentState().path("settings").path("level").asInt()).isEqualTo(3);
    }

    @Test
    void sensor_whenNewReadingArrives_retainsIdentityDespiteReadingIdTimeOrRoomChange() {
        var before = mapper.sensor(fixture.readings.getFirst());
        fixture.environment.setRoom(fixture.living);
        var after = mapper.sensor(TwinFixtures.reading(999, fixture.environment, "TEMPERATURE",
                "28.100", "°C", TwinFixtures.TIME.plusMinutes(1)));
        assertThat(after.sensorId()).isEqualTo(before.sensorId());
        assertThat(after.roomId()).isEqualTo(fixture.living.getId());
        assertThat(after.latestValue()).isEqualByComparingTo("28.100");
        assertThat(after.observedAt()).isEqualTo(TwinFixtures.TIME.plusMinutes(1));
    }
}
