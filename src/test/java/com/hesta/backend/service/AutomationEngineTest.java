package com.hesta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.dto.response.SceneExecutionResponse;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.impl.AutomationEngineImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutomationEngineTest {
    @Mock AutomationRuleRepository ruleRepository;
    @Mock AutomationExecutionRepository executionRepository;
    @Mock DeviceRepository deviceRepository;
    @Mock DeviceCommandService commandService;
    @Mock BehaviorEventRecorder behaviorEventRecorder;
    @Mock ManualOverrideService manualOverrideService;
    @Mock SceneExecutionService sceneExecutionService;
    AutomationEngineImpl engine;
    UUID homeId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    Device device;
    AutomationRule rule;

    @BeforeEach
    void setUp() {
        engine = new AutomationEngineImpl(ruleRepository, executionRepository, deviceRepository, behaviorEventRecorder,
                manualOverrideService, sceneExecutionService, new com.hesta.backend.service.automation.RuleMatcher("Asia/Ho_Chi_Minh"),
                new com.hesta.backend.service.automation.RuleTriggerGate(0),
                new com.hesta.backend.service.execution.CommandBatchRunner(commandService, behaviorEventRecorder));
        Home home = Home.builder().id(homeId).name("Home").build();
        device = Device.builder().id(deviceId).node(EdgeNode.builder().home(home).build())
                .room(Room.builder().home(home).build()).name("Fan").deviceType("FAN").build();
        rule = AutomationRule.builder().id(UUID.randomUUID()).home(home).name("Hot").enabled(true)
                .triggerType(TriggerType.SENSOR).build();
        rule.getConditions().add(RuleCondition.builder().rule(rule).device(device).attribute("temperature")
                .operator(ConditionOperator.GT).expectedValue(JsonNodeFactory.instance.numberNode(30))
                .logicalOperator(LogicalOperator.AND).order(0).build());
        rule.getActions().add(RuleAction.builder().rule(rule).device(device).action("TURN_ON")
                .parameters(Map.of()).order(0).build());
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        lenient().when(ruleRepository.findAllByHomeIdAndEnabledTrueAndTriggerTypeIn(eq(homeId), anyList())).thenReturn(List.of(rule));
    }

    @Test
    void matchingConditionCallsMockBoundaryAndStoresSuccess() {
        when(commandService.sendCommand(eq(deviceId), eq("TURN_ON"), anyMap(), eq(StateChangeSource.AUTOMATION)))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(executionRepository.save(any())).thenAnswer(invocation -> {
            AutomationExecution execution = invocation.getArgument(0); execution.setId(UUID.randomUUID()); return execution;
        });

        var response = engine.process(homeId, event(31));

        assertThat(response.getMatchedRules()).isEqualTo(1);
        assertThat(response.getExecutions().getFirst().getStatus()).isEqualTo("SUCCESS");
        verify(ruleRepository).fetchActionsByIdIn(List.of(rule.getId()));
        verify(commandService).sendCommand(eq(deviceId), eq("TURN_ON"), anyMap(), eq(StateChangeSource.AUTOMATION));
    }

    @Test
    void falseConditionDoesNotCallDeviceService() {
        var response = engine.process(homeId, event(25));

        assertThat(response.getMatchedRules()).isZero();
        verifyNoInteractions(commandService, executionRepository);
    }

    @Test
    void commandFailureIsStoredAsFailedExecution() {
        when(commandService.sendCommand(any(), any(), anyMap(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(false).status("FAILED").errorCode("OFFLINE").build()));
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = engine.process(homeId, event(35));

        assertThat(response.getExecutions().getFirst().getStatus()).isEqualTo("FAILED");
        assertThat(response.getExecutions().getFirst().getResultDetail().get(0).get("errorCode").asText()).isEqualTo("OFFLINE");
    }

    @Test
    void testEventNeverControlsADevice() {
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var event = event(35);
        event.setTest(true);
        var response = engine.process(homeId, event);
        assertThat(response.getExecutions().getFirst().getStatus()).isEqualTo("SKIPPED");
        verifyNoInteractions(commandService);
    }

    @Test
    void manualOverrideSkipsAutomaticCommandAndRecordsOutcome() {
        when(manualOverrideService.isActive(deviceId)).thenReturn(true);
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var response = engine.process(homeId, event(35));
        assertThat(response.getExecutions().getFirst().getStatus()).isEqualTo("SKIPPED");
        assertThat(response.getExecutions().getFirst().getResultDetail().get(0).get("status").asText())
                .isEqualTo("SKIPPED_OVERRIDE");
        verifyNoInteractions(commandService);
    }

    @Test
    void sceneActionUsesSceneExecutionAndStoresNestedResult() {
        UUID sceneId = UUID.randomUUID();
        Scene scene = Scene.builder().id(sceneId).home(rule.getHome()).name("Evening").enabled(true).build();
        scene.getActions().add(SceneAction.builder().scene(scene).targetDevice(device).action("TURN_ON").order(0).build());
        rule.getActions().clear();
        rule.getActions().add(RuleAction.builder().rule(rule).scene(scene).action("EXECUTE_SCENE").order(0).build());
        when(sceneExecutionService.executeFromAutomation(homeId, sceneId)).thenReturn(SceneExecutionResponse.builder()
                .id(UUID.randomUUID()).sceneId(sceneId).status("SUCCESS")
                .resultDetail(new ObjectMapper().createArrayNode()).build());
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = engine.process(homeId, event(35));

        assertThat(response.getExecutions().getFirst().getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getExecutions().getFirst().getResultDetail().get(0).get("sceneId").asText())
                .isEqualTo(sceneId.toString());
        verifyNoInteractions(commandService);
    }

    @Test
    void conflictingRulesDoNotSendOppositeCommandsToOneDevice() {
        rule.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        AutomationRule second = AutomationRule.builder().id(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .home(rule.getHome()).name("Cold").enabled(true).triggerType(TriggerType.SENSOR).build();
        second.getConditions().add(rule.getConditions().getFirst());
        second.getActions().add(RuleAction.builder().rule(second).device(device)
                .action("TURN_OFF").parameters(Map.of()).order(0).build());
        when(ruleRepository.findAllByHomeIdAndEnabledTrueAndTriggerTypeIn(eq(homeId), anyList()))
                .thenReturn(List.of(second, rule));
        when(commandService.sendCommand(eq(deviceId), eq("TURN_ON"), anyMap(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = engine.process(homeId, event(35));

        assertThat(response.getMatchedRules()).isEqualTo(2);
        assertThat(response.getExecutions()).extracting(item -> item.getStatus())
                .containsExactly("SUCCESS", "SKIPPED");
        assertThat(response.getExecutions().get(1).getResultDetail().get(0).get("status").asText())
                .isEqualTo("SKIPPED_CONFLICT");
        verify(commandService, times(1)).sendCommand(eq(deviceId), eq("TURN_ON"), anyMap(), any());
        verify(commandService, never()).sendCommand(eq(deviceId), eq("TURN_OFF"), anyMap(), any());
    }

    @Test
    void stateReportsFireOnlyOnFalseToTrueTransition() {
        when(commandService.sendCommand(eq(deviceId), eq("TURN_ON"), anyMap(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        engine.process(homeId, report(31));
        engine.process(homeId, report(32)); // still hot: no second run
        engine.process(homeId, report(25)); // cooled down: re-arms
        engine.process(homeId, report(33));

        verify(commandService, times(2)).sendCommand(eq(deviceId), eq("TURN_ON"), anyMap(), any());
    }

    @Test
    void conditionOnAnotherDeviceUsesItsCurrentState() {
        Device lightSensor = Device.builder().id(UUID.randomUUID()).name("Lux").deviceType("LIGHT_SENSOR")
                .currentState(Map.of("lux", 5)).build();
        rule.getConditions().add(RuleCondition.builder().rule(rule).device(lightSensor).attribute("lux")
                .operator(ConditionOperator.LT).expectedValue(JsonNodeFactory.instance.numberNode(10))
                .logicalOperator(LogicalOperator.AND).order(1).build());
        when(commandService.sendCommand(eq(deviceId), eq("TURN_ON"), anyMap(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(engine.process(homeId, event(31)).getMatchedRules()).isEqualTo(1);
        lightSensor.setCurrentState(Map.of("lux", 500));
        assertThat(engine.process(homeId, event(31)).getMatchedRules()).isZero();
    }

    @Test
    void andBindsTighterThanOr() {
        // temperature > 30 OR (temperature < 0 AND temperature > 100) -> true for 31
        rule.getConditions().add(RuleCondition.builder().rule(rule).device(device).attribute("temperature")
                .operator(ConditionOperator.LT).expectedValue(JsonNodeFactory.instance.numberNode(0))
                .logicalOperator(LogicalOperator.OR).order(1).build());
        rule.getConditions().add(RuleCondition.builder().rule(rule).device(device).attribute("temperature")
                .operator(ConditionOperator.GT).expectedValue(JsonNodeFactory.instance.numberNode(100))
                .logicalOperator(LogicalOperator.AND).order(2).build());
        when(commandService.sendCommand(eq(deviceId), eq("TURN_ON"), anyMap(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(engine.process(homeId, event(31)).getMatchedRules()).isEqualTo(1);
    }

    @Test
    void disabledSceneIsSkippedWithoutCallingSceneService() {
        Scene scene = Scene.builder().id(UUID.randomUUID()).home(rule.getHome()).name("Off").enabled(false).build();
        rule.getActions().clear();
        rule.getActions().add(RuleAction.builder().rule(rule).scene(scene).action("EXECUTE_SCENE").order(0).build());
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = engine.process(homeId, event(35));

        assertThat(response.getExecutions().getFirst().getResultDetail().get(0).get("status").asText())
                .isEqualTo("SKIPPED_DISABLED");
        verifyNoInteractions(sceneExecutionService);
    }

    private AutomationEventRequest report(int temperature) {
        AutomationEventRequest event = event(temperature);
        event.setStateReport(true);
        return event;
    }

    private AutomationEventRequest event(int temperature) {
        return AutomationEventRequest.builder().sourceDeviceId(deviceId).eventType("SENSOR_READING")
                .data(Map.of("temperature", temperature)).build();
    }

    @Test
    void booleanAndNumericCoercionMatchesConditionsFromHardwareSensors() {
        rule.getConditions().clear();
        rule.getConditions().add(RuleCondition.builder().rule(rule).device(device).attribute("motion")
                .operator(ConditionOperator.EQ).expectedValue(JsonNodeFactory.instance.booleanNode(true))
                .logicalOperator(LogicalOperator.AND).order(0).build());

        when(commandService.sendCommand(eq(deviceId), eq("TURN_ON"), anyMap(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var event = AutomationEventRequest.builder().sourceDeviceId(deviceId).eventType("SENSOR")
                .data(Map.of("motion", 1)).build();
        var response = engine.process(homeId, event);

        assertThat(response.getMatchedRules()).isEqualTo(1);
        assertThat(response.getExecutions().getFirst().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void emergencyRuleBypassesManualOverrideAndExecutesCommand() {
        when(manualOverrideService.isActive(deviceId)).thenReturn(true);
        when(commandService.sendCommand(eq(deviceId), eq("TURN_ON"), any(), eq(StateChangeSource.AUTOMATION)))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("SUCCESS").build()));
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AutomationEventRequest emergencyEvent = AutomationEventRequest.builder()
                .eventType("GAS_LEAK_DETECTED")
                .sourceDeviceId(deviceId)
                .data(Map.of("temperature", 35, "severity", "CRITICAL", "emergency", true))
                .occurredAt(java.time.OffsetDateTime.now())
                .build();

        var response = engine.process(homeId, emergencyEvent);
        assertThat(response.getExecutions().getFirst().getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getExecutions().getFirst().getResultDetail().get(0).get("emergencyBypassed").asBoolean()).isTrue();
        verify(commandService).sendCommand(eq(deviceId), eq("TURN_ON"), any(), eq(StateChangeSource.AUTOMATION));
    }
}
