package com.hesta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AutomationEventRequest;
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
    AutomationEngineImpl engine;
    UUID homeId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    Device device;
    AutomationRule rule;

    @BeforeEach
    void setUp() {
        engine = new AutomationEngineImpl(ruleRepository, executionRepository, deviceRepository, commandService, new ObjectMapper());
        Home home = Home.builder().id(homeId).name("Home").build();
        device = Device.builder().id(deviceId).home(home).name("Fan").deviceType(DeviceType.FAN).build();
        rule = AutomationRule.builder().id(UUID.randomUUID()).home(home).name("Hot").enabled(true)
                .triggerType(TriggerType.SENSOR).build();
        rule.getConditions().add(RuleCondition.builder().rule(rule).device(device).attribute("temperature")
                .operator(ConditionOperator.GT).expectedValue(JsonNodeFactory.instance.numberNode(30))
                .logicalOperator(LogicalOperator.AND).order(0).build());
        rule.getActions().add(RuleAction.builder().rule(rule).device(device).action(DeviceAction.TURN_ON)
                .parameters(Map.of()).order(0).build());
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(ruleRepository.findAllByHomeIdAndEnabledTrueAndTriggerTypeIn(eq(homeId), anyList())).thenReturn(List.of(rule));
    }

    @Test
    void matchingConditionCallsMockBoundaryAndStoresSuccess() {
        when(commandService.sendCommand(eq(deviceId), eq(DeviceAction.TURN_ON), anyMap(), eq(StateChangeSource.AUTOMATION)))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(executionRepository.save(any())).thenAnswer(invocation -> {
            AutomationExecution execution = invocation.getArgument(0); execution.setId(UUID.randomUUID()); return execution;
        });

        var response = engine.process(homeId, event(31));

        assertThat(response.getMatchedRules()).isEqualTo(1);
        assertThat(response.getExecutions().getFirst().getStatus()).isEqualTo("SUCCESS");
        verify(ruleRepository).fetchActionsByIdIn(List.of(rule.getId()));
        verify(commandService).sendCommand(eq(deviceId), eq(DeviceAction.TURN_ON), anyMap(), eq(StateChangeSource.AUTOMATION));
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

    private AutomationEventRequest event(int temperature) {
        return AutomationEventRequest.builder().sourceDeviceId(deviceId).eventType("SENSOR_READING")
                .data(Map.of("temperature", temperature)).build();
    }
}
