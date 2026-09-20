package com.hesta.backend.repository;

import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.TwinLayout;
import com.hesta.backend.entity.TwinNodeLayout;
import com.hesta.backend.entity.TwinRoomLayout;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.TwinNodeType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "HESTA_TEST_DATABASE_URL", matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/.*")
class TwinLayoutRepositoryTest {
    @Autowired EntityManager entityManager;
    @Autowired TwinLayoutRepository layouts;
    @Autowired TwinRoomLayoutRepository roomLayouts;
    @Autowired TwinNodeLayoutRepository nodeLayouts;

    @DynamicPropertySource
    static void localDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("HESTA_TEST_DATABASE_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("HESTA_TEST_DATABASE_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("HESTA_TEST_DATABASE_PASSWORD"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void saveAndReadLayout_persistsRevisionGeometryAndNodePlacement() {
        User user = User.builder().fullName("Layout repository test").email(UUID.randomUUID() + "@example.com")
                .provider(AuthProvider.LOCAL).passwordHash("test-hash").build();
        entityManager.persist(user);
        Home home = Home.builder().name("Layout home").createdBy(user).build();
        entityManager.persist(home);
        Room room = Room.builder().home(home).name("Living room").build();
        entityManager.persist(room);
        entityManager.flush();

        TwinLayout layout = layouts.save(TwinLayout.builder().homeId(home.getId()).revision(1L).build());
        roomLayouts.save(TwinRoomLayout.builder().layoutId(layout.getId()).roomId(room.getId())
                .x(new BigDecimal("0.125")).y(new BigDecimal("0.250"))
                .width(new BigDecimal("0.500")).height(new BigDecimal("0.375")).build());
        nodeLayouts.save(TwinNodeLayout.builder().layoutId(layout.getId()).nodeType(TwinNodeType.DEVICE)
                .nodeId(UUID.randomUUID().toString()).roomId(room.getId())
                .x(new BigDecimal("0.333")).y(new BigDecimal("0.777")).build());
        entityManager.flush();
        entityManager.clear();

        TwinLayout found = layouts.findByHomeIdForUpdate(home.getId()).orElseThrow();
        assertThat(found.getRevision()).isEqualTo(1L);
        assertThat(roomLayouts.findByLayoutIdOrderByRoomId(found.getId())).singleElement().satisfies(row -> {
            assertThat(row.getX()).isEqualByComparingTo(".125");
            assertThat(row.getWidth()).isEqualByComparingTo(".500");
        });
        assertThat(nodeLayouts.findByLayoutIdOrderByNodeTypeAscNodeIdAsc(found.getId())).singleElement()
                .extracting(TwinNodeLayout::getNodeType).isEqualTo(TwinNodeType.DEVICE);
    }

    @Test
    void findByHomeId_withoutSavedLayout_returnsEmpty() {
        assertThat(layouts.findByHomeId(UUID.randomUUID())).isEmpty();
    }
}
