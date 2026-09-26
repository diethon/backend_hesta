package com.hesta.backend.service;

import com.hesta.backend.dto.request.TwinLayoutSaveRequest;
import com.hesta.backend.dto.request.TwinNodeLayoutRequest;
import com.hesta.backend.dto.request.TwinRoomLayoutRequest;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.SensorReading;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.DeviceType;
import com.hesta.backend.service.impl.TwinLayoutServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TwinLayoutServiceImpl.class)
@EnabledIfEnvironmentVariable(named = "HESTA_TEST_DATABASE_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/.*")
class TwinLayoutServiceIntegrationTest {
    @Autowired EntityManager entityManager;
    @Autowired TwinLayoutService service;
    @MockitoBean HomeAuthorizationService authorization;
    private final UUID userId = UUID.randomUUID();
    private Home home;
    private Room room;
    private Device device;

    @DynamicPropertySource
    static void localDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("HESTA_TEST_DATABASE_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("HESTA_TEST_DATABASE_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("HESTA_TEST_DATABASE_PASSWORD"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void setUp() {
        User user = User.builder().fullName("Layout service integration").email(UUID.randomUUID() + "@example.com")
                .provider(AuthProvider.LOCAL).passwordHash("test-hash").build();
        entityManager.persist(user);
        home = Home.builder().name("Layout service home").createdBy(user).build();
        entityManager.persist(home);
        room = Room.builder().home(home).name("Living").build();
        entityManager.persist(room);
        device = Device.builder().home(home).room(room).name("Layout device").deviceType(DeviceType.LIGHT)
                .status(DeviceStatus.UNKNOWN).currentState(Map.of()).build();
        entityManager.persist(device);
        entityManager.persist(SensorReading.builder().device(device).metricType("TEMPERATURE")
                .value(new BigDecimal("21.500")).unit("C")
                .recordedAt(OffsetDateTime.parse("2026-09-17T09:00:00Z")).build());
        entityManager.flush();
        when(authorization.requireLayoutManagement(userId, home.getId())).thenReturn(home);
        when(authorization.requireAccess(userId, home.getId())).thenReturn(home);
    }

    @Test
    void saveThenReplace_removesOmittedPlacementsAndAdvancesRevision() {
        var first = service.saveLayout(userId, home.getId(), new TwinLayoutSaveRequest(0L,
                List.of(new TwinRoomLayoutRequest(room.getId(), 2, bd(".1"), bd(".1"), bd(".5"), bd(".5"))),
                List.of(new TwinNodeLayoutRequest(com.hesta.backend.enums.TwinNodeType.DEVICE,
                        device.getId().toString(), room.getId(), bd(".2"), bd(".2")),
                        new TwinNodeLayoutRequest(com.hesta.backend.enums.TwinNodeType.SENSOR,
                                device.getId() + ":TEMPERATURE", room.getId(), bd(".3"), bd(".3")))));
        assertThat(first.revision()).isEqualTo(1L);
        assertThat(first.rooms()).hasSize(1);
        assertThat(first.rooms().getFirst().floor()).isEqualTo(2);
        assertThat(first.nodes()).hasSize(2);

        var second = service.saveLayout(userId, home.getId(), new TwinLayoutSaveRequest(1L, List.of(), List.of()));
        assertThat(second.revision()).isEqualTo(2L);
        assertThat(service.getLayout(userId, home.getId()).rooms()).isEmpty();
        assertThat(service.getLayout(userId, home.getId()).nodes()).isEmpty();
    }

    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
