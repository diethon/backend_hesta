package com.hesta.backend.repository;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.DeviceStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:hesta-legacy-scene;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class SceneActionLegacySchemaTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private SceneRepository sceneRepository;
    @Autowired
    private SceneActionRepository sceneActionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void emptyJsonObjectPersistsAgainstLegacyNotNullColumn() {
        jdbcTemplate.execute("ALTER TABLE scene_actions ALTER COLUMN target_state SET NOT NULL");

        User owner = userRepository.save(User.builder()
                .fullName("Scene Owner")
                .email("legacy-scene-owner@example.com")
                .passwordHash("hash")
                .provider(AuthProvider.LOCAL)
                .build());
        Home home = homeRepository.save(Home.builder().name("Legacy Home").createdBy(owner).build());
        Room room = roomRepository.save(Room.builder().home(home).name("Living room").build());
        Device device = deviceRepository.save(Device.builder()
                .room(room)
                .name("Light")
                .deviceType("LIGHT")
                .status(DeviceStatus.UNKNOWN)
                .currentState(Map.of())
                .build());
        Scene scene = sceneRepository.save(Scene.builder().home(home).name("Morning").enabled(true).build());

        SceneAction action = sceneActionRepository.saveAndFlush(SceneAction.builder()
                .scene(scene)
                .targetDevice(device)
                .action("TURN_ON")
                .value(JsonNodeFactory.instance.objectNode())
                .order(0)
                .build());

        Integer storedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scene_actions WHERE id = ? AND target_state IS NOT NULL",
                Integer.class, action.getId());
        assertThat(storedRows).isEqualTo(1);
    }
}
