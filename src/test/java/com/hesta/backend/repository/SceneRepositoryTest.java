package com.hesta.backend.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SceneRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HomeRepository homeRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private SceneRepository sceneRepository;

    @Autowired
    private SceneActionRepository sceneActionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Home home;
    private Device device;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder()
                .fullName("Scene Owner")
                .email("scene-owner@example.com")
                .passwordHash("hash")
                .provider(AuthProvider.LOCAL)
                .build());
        home = homeRepository.save(Home.builder()
                .name("Scene Home")
                .createdBy(owner)
                .build());
        device = deviceRepository.save(Device.builder()
                .home(home)
                .name("Living room light")
                .deviceType("LIGHT")
                .build());
    }

    @Test
    void createFindUpdateAndDeleteScene() {
        Scene scene = sceneRepository.saveAndFlush(Scene.builder()
                .home(home)
                .name("Movie time")
                .description("Dim the living room")
                .enabled(true)
                .build());

        Scene found = sceneRepository.findById(scene.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("Movie time");
        assertThat(found.getHome().getId()).isEqualTo(home.getId());
        assertThat(found.getCreatedAt()).isNotNull();

        found.setName("Cinema time");
        found.setEnabled(false);
        sceneRepository.saveAndFlush(found);
        Scene updated = sceneRepository.findById(scene.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Cinema time");
        assertThat(updated.isEnabled()).isFalse();

        sceneRepository.delete(updated);
        sceneRepository.flush();
        assertThat(sceneRepository.findById(scene.getId())).isEmpty();
    }

    @Test
    void createsMultipleActionsAndReturnsThemInOrder() throws Exception {
        Scene scene = sceneRepository.save(Scene.builder()
                .home(home)
                .name("Ordered scene")
                .enabled(true)
                .build());

        sceneActionRepository.save(SceneAction.builder()
                .scene(scene)
                .targetDevice(device)
                .action("SET_BRIGHTNESS")
                .value(objectMapper.readTree("80"))
                .order(2)
                .build());
        sceneActionRepository.save(SceneAction.builder()
                .scene(scene)
                .targetDevice(device)
                .action("POWER")
                .value(objectMapper.readTree("\"ON\""))
                .order(0)
                .build());

        List<SceneAction> actions = sceneActionRepository.findAllBySceneIdOrderByOrderAsc(scene.getId());

        assertThat(actions).extracting(SceneAction::getOrder).containsExactly(0, 2);
        assertThat(actions).allSatisfy(action -> {
            assertThat(action.getCreatedAt()).isNotNull();
            assertThat(action.getUpdatedAt()).isNotNull();
        });
    }

    @Test
    void rejectsDuplicateOrderWithinScene() throws Exception {
        Scene scene = sceneRepository.save(Scene.builder()
                .home(home)
                .name("Unique order scene")
                .enabled(true)
                .build());
        sceneActionRepository.saveAndFlush(SceneAction.builder()
                .scene(scene)
                .targetDevice(device)
                .action("POWER")
                .value(objectMapper.readTree("true"))
                .order(0)
                .build());

        assertThatThrownBy(() -> sceneActionRepository.saveAndFlush(SceneAction.builder()
                .scene(scene)
                .targetDevice(device)
                .action("SET_BRIGHTNESS")
                .value(objectMapper.readTree("50"))
                .order(0)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingSceneRemovesItsActions() throws Exception {
        Scene scene = sceneRepository.save(Scene.builder()
                .home(home)
                .name("Temporary scene")
                .enabled(true)
                .build());
        scene.getActions().add(SceneAction.builder()
                .scene(scene)
                .targetDevice(device)
                .action("POWER")
                .value(objectMapper.readTree("\"OFF\""))
                .order(0)
                .build());
        sceneRepository.saveAndFlush(scene);

        sceneRepository.delete(scene);
        sceneRepository.flush();

        assertThat(sceneActionRepository.countBySceneId(scene.getId())).isZero();
    }
}
