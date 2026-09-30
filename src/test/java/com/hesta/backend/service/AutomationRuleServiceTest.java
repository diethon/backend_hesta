package com.hesta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hesta.backend.dto.request.*;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.ConditionOperator;
import com.hesta.backend.enums.LogicalOperator;
import com.hesta.backend.enums.TriggerType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.impl.AutomationRuleServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutomationRuleServiceTest {
    @Mock HomeAuthorizationService authorizationService;
    @Mock AutomationRuleRepository ruleRepository;
    @Mock AutomationExecutionRepository executionRepository;
    @Mock DeviceRepository deviceRepository;
    @Mock SceneRepository sceneRepository;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks AutomationRuleServiceImpl service;

    UUID userId = UUID.randomUUID();
    UUID homeId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    Home home;
    Device device;

    @BeforeEach
    void setUp() {
        home = Home.builder().id(homeId).name("Home").build();
        device = Device.builder().id(deviceId).node(EdgeNode.builder().home(home).build())
                .room(Room.builder().home(home).build()).name("Fan").deviceType("FAN")
                .capabilities(java.util.Map.of("FAN", java.util.List.of("TURN_ON", "SET_SPEED"))).build();
    }

    @Test
    void createsAndReadsValidRule() {
        device.setNode(null);
        when(authorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(deviceRepository.findAllById(any())).thenReturn(List.of(device));
        when(ruleRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            AutomationRule rule = invocation.getArgument(0);
            assertThat(rule.getConditions().getFirst().getExpectedValue().isIntegralNumber()).isTrue();
            assertThat(rule.getConditions().getFirst().getExpectedValue().intValue()).isEqualTo(30);
            rule.setId(UUID.randomUUID());
            return rule;
        });

        var response = service.create(userId, homeId, validRequest());

        assertThat(response.getName()).isEqualTo("Hot room");
        assertThat(response.getConditions()).hasSize(1);
        assertThat(response.getActions()).extracting("action").containsExactly("SET_SPEED");
    }

    @Test
    void scheduleRuleNeedsActionsButMayOmitSensorConditions() {
        when(authorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(deviceRepository.findAllById(any())).thenReturn(List.of(device));
        when(ruleRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            AutomationRule rule = invocation.getArgument(0);
            rule.setId(UUID.randomUUID());
            return rule;
        });
        CreateAutomationRuleRequest request = validRequest();
        request.setTriggerType("SCHEDULE");
        request.setConditions(List.of());

        var response = service.create(userId, homeId, request);

        assertThat(response.getTriggerType()).isEqualTo("SCHEDULE");
        assertThat(response.getConditions()).isEmpty();
        assertThat(response.getActions()).hasSize(1);
    }

    @Test
    void createsRuleActionForSceneInSameHome() {
        UUID sceneId = UUID.randomUUID();
        Scene scene = Scene.builder().id(sceneId).home(home).name("Evening").enabled(true).build();
        scene.getActions().add(SceneAction.builder().scene(scene).targetDevice(device).action("TURN_ON").order(0).build());
        when(authorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(sceneRepository.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(ruleRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            AutomationRule rule = invocation.getArgument(0);
            rule.setId(UUID.randomUUID());
            return rule;
        });
        CreateAutomationRuleRequest request = validRequest();
        request.setTriggerType("SCHEDULE");
        request.setConditions(List.of());
        request.setActions(List.of(RuleActionRequest.builder().sceneId(sceneId)
                .action("EXECUTE_SCENE").order(0).build()));

        var response = service.create(userId, homeId, request);

        assertThat(response.getActions().getFirst().getSceneId()).isEqualTo(sceneId);
        assertThat(response.getActions().getFirst().getDeviceId()).isNull();
    }

    @Test
    void rejectsInvalidConditionOperator() {
        CreateAutomationRuleRequest request = validRequest();
        request.getConditions().getFirst().setOperator("CONTAINS");
        when(authorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(deviceRepository.findAllById(any())).thenReturn(List.of(device));

        assertThatThrownBy(() -> service.create(userId, homeId, request))
                .isInstanceOf(AppException.class)
                .extracting(exception -> ((AppException) exception).getErrorCode())
                .isEqualTo(ErrorCode.AUTOMATION_CONDITION_INVALID);
        verify(ruleRepository, never()).saveAndFlush(any());
    }

    @Test
    void readsRuleOnlyAfterHomeAccessCheck() {
        UUID ruleId = UUID.randomUUID();
        AutomationRule rule = persistedRule(ruleId);
        when(authorizationService.requireAccess(userId, homeId)).thenReturn(home);
        when(ruleRepository.findByIdAndHomeId(ruleId, homeId)).thenReturn(Optional.of(rule));

        var response = service.get(userId, homeId, ruleId);

        assertThat(response.getId()).isEqualTo(ruleId);
        assertThat(response.getConditions()).hasSize(1);
        verify(authorizationService).requireAccess(userId, homeId);
        verify(ruleRepository).fetchActionsByIdIn(List.of(ruleId));
    }

    @Test
    void listsRulesWithBothCollectionsLoaded() {
        UUID ruleId = UUID.randomUUID();
        when(authorizationService.requireAccess(userId, homeId)).thenReturn(home);
        when(ruleRepository.findAllByHomeIdOrderByNameAsc(homeId)).thenReturn(List.of(persistedRule(ruleId)));

        var responses = service.getAll(userId, homeId);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().getConditions()).hasSize(1);
        assertThat(responses.getFirst().getActions()).hasSize(1);
        verify(ruleRepository).fetchActionsByIdIn(List.of(ruleId));
    }

    @Test
    void updatesRuleAndReplacesItsChildren() {
        UUID ruleId = UUID.randomUUID();
        AutomationRule rule = persistedRule(ruleId);
        UpdateAutomationRuleRequest request = UpdateAutomationRuleRequest.builder()
                .name(" Cooler room ").description("Updated").triggerType("EVENT").enabled(false)
                .conditions(validRequest().getConditions()).actions(validRequest().getActions()).build();
        when(authorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(ruleRepository.findByIdAndHomeId(ruleId, homeId)).thenReturn(Optional.of(rule));
        when(deviceRepository.findAllById(any())).thenReturn(List.of(device));
        when(ruleRepository.saveAndFlush(rule)).thenReturn(rule);

        var response = service.update(userId, homeId, ruleId, request);

        assertThat(response.getName()).isEqualTo("Cooler room");
        assertThat(response.getTriggerType()).isEqualTo("EVENT");
        assertThat(response.isEnabled()).isFalse();
        assertThat(response.getActions()).hasSize(1);
    }

    @Test
    void togglesRuleWithoutReplacingConfiguration() {
        UUID ruleId = UUID.randomUUID();
        AutomationRule rule = persistedRule(ruleId);
        when(authorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(ruleRepository.findByIdAndHomeId(ruleId, homeId)).thenReturn(Optional.of(rule));
        when(ruleRepository.saveAndFlush(rule)).thenReturn(rule);

        var response = service.setEnabled(userId, homeId, ruleId, false);

        assertThat(response.isEnabled()).isFalse();
        assertThat(response.getConditions()).hasSize(1);
        assertThat(response.getActions()).hasSize(1);
    }

    @Test
    void deletesOnlyRuleInsideAuthorizedHome() {
        UUID ruleId = UUID.randomUUID();
        AutomationRule rule = AutomationRule.builder().id(ruleId).home(home).name("Rule").build();
        when(authorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(ruleRepository.findByIdAndHomeId(ruleId, homeId)).thenReturn(Optional.of(rule));

        service.delete(userId, homeId, ruleId);

        verify(ruleRepository).delete(rule);
    }

    private CreateAutomationRuleRequest validRequest() {
        return CreateAutomationRuleRequest.builder().name("Hot room").triggerType("SENSOR").enabled(true)
                .conditions(List.of(RuleConditionRequest.builder().deviceId(deviceId).attribute("temperature")
                        .operator("GT").expectedValue(30).logicalOperator("AND").order(0).build()))
                .actions(List.of(RuleActionRequest.builder().deviceId(deviceId).action("SET_SPEED")
                        .parameters(Map.of("speed", 80)).order(0).build())).build();
    }

    private AutomationRule persistedRule(UUID ruleId) {
        AutomationRule rule = AutomationRule.builder().id(ruleId).home(home).name("Hot room")
                .triggerType(TriggerType.SENSOR).enabled(true).build();
        rule.getConditions().add(RuleCondition.builder().id(UUID.randomUUID()).rule(rule).device(device)
                .attribute("temperature").operator(ConditionOperator.GT)
                .expectedValue(JsonNodeFactory.instance.numberNode(30))
                .logicalOperator(LogicalOperator.AND).order(0).build());
        rule.getActions().add(RuleAction.builder().id(UUID.randomUUID()).rule(rule).device(device)
                .action("SET_SPEED").parameters(Map.of("speed", 80)).order(0).build());
        return rule;
    }
}
