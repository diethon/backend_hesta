package com.hesta.backend.repository;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hesta.backend.entity.AutomationRule;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.RuleAction;
import com.hesta.backend.entity.RuleCondition;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.ConditionOperator;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.DeviceType;
import com.hesta.backend.enums.LogicalOperator;
import com.hesta.backend.enums.TriggerType;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AutomationRuleRepositoryTest {
    @Autowired private UserRepository userRepository;
    @Autowired private HomeRepository homeRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private AutomationRuleRepository ruleRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void ruleQueriesLoadBothCollectionsWithoutDuplicateRules() {
        User owner = userRepository.save(User.builder().fullName("Automation Owner")
                .email("automation-owner@example.com").passwordHash("hash")
                .provider(AuthProvider.LOCAL).build());
        Home home = homeRepository.save(Home.builder().name("Automation Home").createdBy(owner).build());
        Device device = deviceRepository.save(Device.builder().home(home).name("Fan")
                .deviceType(DeviceType.FAN).status(DeviceStatus.UNKNOWN).currentState(Map.of()).build());
        UUID firstId = saveRule(home, device, "A rule");
        saveRule(home, device, "B rule");
        entityManager.flush();
        entityManager.clear();

        List<AutomationRule> rules = ruleRepository.findAllByHomeIdOrderByNameAsc(home.getId());
        assertThat(rules).extracting(AutomationRule::getName).containsExactly("A rule", "B rule");
        assertActionsLoadedAfterSecondQuery(rules);

        entityManager.clear();
        AutomationRule single = ruleRepository.findByIdAndHomeId(firstId, home.getId()).orElseThrow();
        assertActionsLoadedAfterSecondQuery(List.of(single));

        entityManager.clear();
        List<AutomationRule> enabled = ruleRepository.findAllByHomeIdAndEnabledTrueAndTriggerTypeIn(
                home.getId(), List.of(TriggerType.SENSOR));
        assertThat(enabled).hasSize(2);
        assertActionsLoadedAfterSecondQuery(enabled);
    }

    private void assertActionsLoadedAfterSecondQuery(List<AutomationRule> rules) {
        assertThat(rules).allSatisfy(rule -> {
            assertThat(Hibernate.isInitialized(rule.getConditions())).isTrue();
            assertThat(rule.getConditions()).hasSize(2);
            assertThat(rule.getConditions()).extracting(RuleCondition::getOrder).containsExactly(0, 1);
            assertThat(rule.getConditions()).allSatisfy(condition -> {
                assertThat(condition.getDevice().getName()).isEqualTo("Fan");
                assertThat(condition.getExpectedValue().intValue()).isEqualTo(30);
            });
        });

        ruleRepository.fetchActionsByIdIn(rules.stream().map(AutomationRule::getId).toList());

        assertThat(rules).allSatisfy(rule -> {
            assertThat(Hibernate.isInitialized(rule.getActions())).isTrue();
            assertThat(rule.getActions()).hasSize(2);
            assertThat(rule.getActions()).extracting(RuleAction::getOrder).containsExactly(0, 1);
            assertThat(rule.getActions()).allSatisfy(action ->
                    assertThat(action.getDevice().getName()).isEqualTo("Fan"));
        });
    }

    private UUID saveRule(Home home, Device device, String name) {
        AutomationRule rule = AutomationRule.builder().home(home).name(name)
                .triggerType(TriggerType.SENSOR).enabled(true).build();
        for (int order = 0; order < 2; order++) {
            rule.getConditions().add(RuleCondition.builder().rule(rule).device(device)
                    .attribute("temperature").operator(ConditionOperator.GT)
                    .expectedValue(JsonNodeFactory.instance.numberNode(30))
                    .logicalOperator(LogicalOperator.AND).order(order).build());
            rule.getActions().add(RuleAction.builder().rule(rule).device(device)
                    .action(DeviceAction.TURN_ON).parameters(Map.of()).order(order).build());
        }
        return ruleRepository.save(rule).getId();
    }
}
