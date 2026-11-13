package com.hesta.backend.repository;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hesta.backend.entity.AutomationRecommendation;
import com.hesta.backend.entity.AutomationRule;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.RuleAction;
import com.hesta.backend.entity.RuleCondition;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.ConditionOperator;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.LogicalOperator;
import com.hesta.backend.enums.TriggerType;
import com.hesta.backend.service.automation.DeviceReferenceCleaner;
import com.hesta.backend.service.automation.RuleTriggerGate;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against the real schema so the (parent, order) unique constraints are checked while renumbering. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeviceReferenceCleanerTest {
    @Autowired private UserRepository userRepository;
    @Autowired private HomeRepository homeRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private SceneRepository sceneRepository;
    @Autowired private SceneActionRepository sceneActionRepository;
    @Autowired private AutomationRuleRepository ruleRepository;
    @Autowired private AutomationRecommendationRepository recommendationRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void removedDeviceLeavesScenesRulesAndRecommendations() {
        User owner = userRepository.save(User.builder().fullName("Owner").email("cleaner-owner@example.com")
                .passwordHash("hash").provider(AuthProvider.LOCAL).build());
        Home home = homeRepository.save(Home.builder().name("Cleaner Home").createdBy(owner).build());
        Room room = roomRepository.save(Room.builder().home(home).name("Living room").build());
        Device lamp = device(room, "Lamp");
        Device fan = device(room, "Fan");
        Device sensor = device(room, "Sensor");

        // Scene: lamp, fan, sensor -> lamp, sensor with orders 0, 1
        Scene scene = Scene.builder().home(home).name("Evening").enabled(true).build();
        List.of(lamp, fan, sensor).forEach(device -> scene.getActions().add(SceneAction.builder().scene(scene)
                .targetDevice(device).action("TURN_ON").order(scene.getActions().size()).build()));
        UUID sceneId = sceneRepository.save(scene).getId();

        UUID keepsRunning = rule(home, "Fan then lamp", sensor, List.of(fan, lamp)).getId();
        UUID watchesFan = rule(home, "Fan is on", fan, List.of(lamp)).getId();
        UUID onlyFan = rule(home, "Only fan", sensor, List.of(fan)).getId();
        UUID recommendationId = recommendationRepository.save(AutomationRecommendation.builder().home(home).device(fan)
                .triggerCondition(Map.of("type", "SCHEDULE", "time", "07:00"))
                .proposedAction(Map.of("deviceId", fan.getId().toString(), "action", "TURN_ON"))
                .explanation("habit").confidenceScore(BigDecimal.valueOf(0.9)).status("PENDING")
                .engineType("ML").modelVersion("test").build()).getId();
        entityManager.flush();
        entityManager.clear();

        new DeviceReferenceCleaner(sceneActionRepository, sceneRepository, ruleRepository, recommendationRepository,
                new RuleTriggerGate(0)).detach(fan.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(sceneActionRepository.findAllBySceneIdOrderByOrderAsc(sceneId))
                .extracting(action -> action.getTargetDevice().getName(), SceneAction::getOrder)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Lamp", 0), org.assertj.core.groups.Tuple.tuple("Sensor", 1));

        AutomationRule kept = loadRule(keepsRunning, home);
        assertThat(kept.isEnabled()).isTrue();
        assertThat(kept.getActions()).extracting(action -> action.getDevice().getName(), RuleAction::getOrder)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Lamp", 0));

        assertThat(loadRule(watchesFan, home).isEnabled()).isFalse();
        AutomationRule empty = loadRule(onlyFan, home);
        assertThat(empty.isEnabled()).isFalse();
        assertThat(empty.getActions()).isEmpty();

        assertThat(recommendationRepository.findById(recommendationId).orElseThrow().getStatus()).isEqualTo("EXPIRED");
    }

    private Device device(Room room, String name) {
        return deviceRepository.save(Device.builder().room(room).name(name).deviceType("LIGHT")
                .status(DeviceStatus.UNKNOWN).currentState(Map.of()).build());
    }

    private AutomationRule rule(Home home, String name, Device watched, List<Device> targets) {
        AutomationRule rule = AutomationRule.builder().home(home).name(name)
                .triggerType(TriggerType.SENSOR).enabled(true).build();
        rule.getConditions().add(RuleCondition.builder().rule(rule).device(watched).attribute("power")
                .operator(ConditionOperator.EQ).expectedValue(JsonNodeFactory.instance.textNode("ON"))
                .logicalOperator(LogicalOperator.AND).order(0).build());
        for (Device target : targets) {
            rule.getActions().add(RuleAction.builder().rule(rule).device(target).action("TURN_ON")
                    .parameters(Map.of()).order(rule.getActions().size()).build());
        }
        return ruleRepository.save(rule);
    }

    private AutomationRule loadRule(UUID id, Home home) {
        AutomationRule rule = ruleRepository.findByIdAndHomeId(id, home.getId()).orElseThrow();
        ruleRepository.fetchActionsByIdIn(List.of(id));
        rule.getActions().sort(Comparator.comparingInt(RuleAction::getOrder));
        return rule;
    }
}
