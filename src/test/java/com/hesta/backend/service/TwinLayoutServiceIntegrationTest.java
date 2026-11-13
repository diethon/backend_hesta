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
        device = Device.builder().room(room).name("Layout device").deviceType("LIGHT")
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

    @Test
    void architecture_roundTripsPostgresJsonbAndLegacyPutPreservesIt() {
        var point = new com.hesta.backend.dto.request.TwinArchitectureRequest.Point(0, 0);
        var outline = List.of(point, new com.hesta.backend.dto.request.TwinArchitectureRequest.Point(1, 0),
                new com.hesta.backend.dto.request.TwinArchitectureRequest.Point(1, 1), new com.hesta.backend.dto.request.TwinArchitectureRequest.Point(0, 1));
        var furniture = new com.hesta.backend.dto.request.TwinArchitectureRequest.ObjectPlacement("sofa-1",
                com.hesta.backend.dto.request.TwinArchitectureRequest.Kind.SOFA, 1, 1, 2.4, .9, .8, 90, null, null);
        var architecture = new com.hesta.backend.dto.request.TwinArchitectureRequest(1,
                Map.of(room.getId(), new com.hesta.backend.dto.request.TwinArchitectureRequest.RoomGeometry(
                        com.hesta.backend.dto.request.TwinArchitectureRequest.Shape.RECTANGLE, outline, null, null, 2.7, .15, List.of(furniture), false)),
                Map.of("DEVICE:" + device.getId(), 90.0), Map.of(2, new com.hesta.backend.dto.request.TwinArchitectureRequest.FloorGeometry(0, 2.7, .22)));
        var roomGeometry = List.of(new TwinRoomLayoutRequest(room.getId(), 2, bd(".1"), bd(".1"), bd(".5"), bd(".5")));
        var nodes = List.of(new TwinNodeLayoutRequest(com.hesta.backend.enums.TwinNodeType.DEVICE,
                device.getId().toString(), room.getId(), bd(".2"), bd(".2")));
        UUID savedHomeId = home.getId();
        service.saveLayout(userId, savedHomeId, new TwinLayoutSaveRequest(0L, roomGeometry, nodes, architecture));
        entityManager.flush(); entityManager.clear(); // Actual DB read, not the JPA first-level cache.
        assertThat(service.getLayout(userId, savedHomeId).architecture()).isEqualTo(architecture);
        assertThat(entityManager.createNativeQuery("SELECT jsonb_typeof(architecture) FROM twin_layouts WHERE home_id=:id")
                .setParameter("id", savedHomeId).getSingleResult()).isEqualTo("object");
        var legacy = service.saveLayout(userId, savedHomeId, new TwinLayoutSaveRequest(1L, roomGeometry, nodes));
        assertThat(legacy.revision()).isEqualTo(2);
        assertThat(legacy.architecture()).isEqualTo(architecture);
        var removed = service.saveLayout(userId, savedHomeId, new TwinLayoutSaveRequest(2L, List.of(), List.of()));
        assertThat(removed.architecture().rooms()).isEmpty();
        assertThat(removed.architecture().nodeRotations()).isEmpty();
    }
}
