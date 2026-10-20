package com.hesta.backend.repository;

import com.hesta.backend.entity.*;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.EdgeNodeStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "HESTA_TEST_DATABASE_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/.*")
class TwinSnapshotRepositoryTest {
    @Autowired EntityManager entityManager;
    @Autowired TwinSnapshotRepository repository;

    @DynamicPropertySource
    static void localDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("HESTA_TEST_DATABASE_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("HESTA_TEST_DATABASE_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("HESTA_TEST_DATABASE_PASSWORD"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void includesRoomAndUnassignedNodeDevicesWithLatestMetricsWithoutDeletedOrForeignNodes() {
        var owner = User.builder().fullName("Twin test").email(UUID.randomUUID() + "@example.com")
                .provider(AuthProvider.LOCAL).passwordHash("test-hash").build();
        entityManager.persist(owner);
        var home = home(owner);
        var foreignHome = home(owner);
        var room = Room.builder().home(home).name("Room").build();
        entityManager.persist(room);
        var node = node(home);
        var foreignNode = node(foreignHome);
        var assigned = device(room, null, "LED_RGB", false);
        var unassigned = device(null, node, "TEMP_HUMID_SENSOR", false);
        var deleted = device(null, node, "SMOKE_SENSOR", true);
        var foreign = device(null, foreignNode, "MOTION_SENSOR", false);
        // A room takes precedence over a conflicting node, matching Device.getHome().
        var roomWins = device(room, foreignNode, "SMART_PLUG", false);
        var now = OffsetDateTime.parse("2026-10-01T09:00:00Z");
        reading(unassigned, "TEMPERATURE", "20", now.minusMinutes(1));
        reading(unassigned, "TEMPERATURE", "25", now);
        var latest = reading(unassigned, "TEMPERATURE", "26", now);
        var humidity = reading(unassigned, "HUMIDITY", "60", now);
        reading(deleted, "SMOKE", "99", now);
        reading(foreign, "MOTION", "1", now);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findDevicesByHomeId(home.getId())).extracting(Device::getId)
                .containsExactlyInAnyOrder(assigned.getId(), unassigned.getId(), roomWins.getId());
        assertThat(repository.findDevicesByHomeId(foreignHome.getId())).extracting(Device::getId)
                .containsExactly(foreign.getId());
        assertThat(repository.findLatestReadingsByHomeId(home.getId())).extracting(SensorReading::getId)
                .containsExactlyInAnyOrder(latest.getId(), humidity.getId());
        assertThat(repository.findDevicesByHomeId(UUID.randomUUID())).isEmpty();
    }

    private Home home(User owner) {
        var home = Home.builder().name("Twin test").createdBy(owner).build();
        entityManager.persist(home);
        return home;
    }

    private EdgeNode node(Home home) {
        var node = EdgeNode.builder().home(home).nodeCode(UUID.randomUUID().toString())
                .status(EdgeNodeStatus.OFFLINE).build();
        entityManager.persist(node);
        return node;
    }

    private Device device(Room room, EdgeNode node, String type, boolean deleted) {
        var device = Device.builder().room(room).node(node).name(type).deviceType(type)
                .status(DeviceStatus.UNKNOWN).currentState(Map.of()).isDeleted(deleted).build();
        entityManager.persist(device);
        return device;
    }

    private SensorReading reading(Device device, String metric, String value, OffsetDateTime time) {
        var reading = SensorReading.builder().device(device).metricType(metric)
                .value(new BigDecimal(value)).unit("raw").recordedAt(time).build();
        entityManager.persist(reading);
        return reading;
    }
}
