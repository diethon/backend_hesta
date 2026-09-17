package com.hesta.backend.repository;

import com.hesta.backend.entity.*;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.DeviceType;
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

/** Runs only against explicitly configured local PostgreSQL; all data rolls back. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "HESTA_TEST_DATABASE_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/.*")
class SensorReadingRepositoryTest {
    @Autowired EntityManager entityManager;
    @Autowired SensorReadingRepository repository;
    @Autowired DeviceRepository deviceRepository;

    @DynamicPropertySource
    static void localDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("HESTA_TEST_DATABASE_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("HESTA_TEST_DATABASE_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("HESTA_TEST_DATABASE_PASSWORD"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void findLatestByHomeId_multipleStreamsAndTies_returnsLatestPerMetricWithoutDeletedOrForeignDevices() {
        var user = User.builder().fullName("Twin repository test").email(UUID.randomUUID() + "@example.com")
                .provider(AuthProvider.LOCAL).passwordHash("test-hash").build();
        entityManager.persist(user);
        Home home = home(user);
        Home otherHome = home(user);
        Room room = Room.builder().home(home).name("Bedroom").build();
        entityManager.persist(room);
        Device device = device(home, room, false);
        Device unassigned = device(home, null, false);
        Device deleted = device(home, room, true);
        Device foreign = device(otherHome, null, false);
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T09:00:00Z");
        reading(device, "TEMPERATURE", "20", now.minusMinutes(1));
        reading(device, "TEMPERATURE", "25", now);
        SensorReading winner = reading(device, "TEMPERATURE", "26", now);
        SensorReading humidity = reading(device, "HUMIDITY", "61", now.minusHours(1));
        SensorReading detached = reading(unassigned, "TEMPERATURE", "29", now);
        reading(deleted, "TEMPERATURE", "99", now);
        reading(foreign, "TEMPERATURE", "99", now);
        entityManager.flush();
        entityManager.clear();

        var result = repository.findLatestByHomeId(home.getId());

        assertThat(result).extracting(SensorReading::getId)
                .containsExactlyInAnyOrder(winner.getId(), humidity.getId(), detached.getId());
        assertThat(result).allSatisfy(reading -> {
            assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                    .isLoaded(reading, "device")).isTrue();
            assertThat(reading.getDevice().getHome().getId()).isEqualTo(home.getId());
        });
        assertThat(deviceRepository.findByHomeIdOrderByIdAsc(home.getId()))
                .extracting(Device::getId).containsExactlyInAnyOrder(device.getId(), unassigned.getId());
        assertThat(repository.findLatestByHomeId(UUID.randomUUID())).isEmpty();
        assertThat(deviceRepository.findHealthReferences()).filteredOn(row -> row.getHomeId().equals(home.getId()))
                .extracting(TwinHealthReference::getDeviceId).containsExactlyInAnyOrder(device.getId(), unassigned.getId());
        assertThat(repository.findHealthReferences()).filteredOn(row -> row.getHomeId().equals(home.getId())).hasSize(3);
        assertThat(repository.findHealthReference(unassigned.getId(), "TEMPERATURE")).get().satisfies(row -> {
            assertThat(row.getRoomId()).isNull();
            assertThat(row.getHomeId()).isEqualTo(home.getId());
            assertThat(row.getReferenceTime().toInstant()).isEqualTo(now.toInstant());
        });
        assertThat(deviceRepository.findHealthReference(deleted.getId())).isEmpty();
        assertThat(repository.findHealthReference(deleted.getId(), "TEMPERATURE")).isEmpty();
    }

    private Home home(User user) {
        Home home = Home.builder().name("Twin query test").createdBy(user).build();
        entityManager.persist(home);
        return home;
    }

    private Device device(Home home, Room room, boolean deleted) {
        Device device = Device.builder().home(home).room(room).name("Sensor").deviceType(DeviceType.SENSOR)
                .status(DeviceStatus.UNKNOWN).currentState(Map.of()).isDeleted(deleted).build();
        entityManager.persist(device);
        return device;
    }

    private SensorReading reading(Device device, String metric, String value, OffsetDateTime time) {
        SensorReading reading = SensorReading.builder().device(device).metricType(metric)
                .value(new BigDecimal(value)).unit("raw").recordedAt(time).build();
        entityManager.persist(reading);
        return reading;
    }
}
