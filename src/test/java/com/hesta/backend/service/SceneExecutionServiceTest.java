package com.hesta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.entity.SceneExecution;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.repository.SceneExecutionRepository;
import com.hesta.backend.repository.SceneRepository;
import com.hesta.backend.service.impl.SceneExecutionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SceneExecutionServiceTest {
    @Mock HomeAuthorizationService authorization;
    @Mock SceneRepository scenes;
    @Mock SceneExecutionRepository executions;
    @Mock DeviceCommandService commands;
    @Mock BehaviorEventRecorder behaviorEventRecorder;
    @Mock ManualOverrideService manualOverrideService;
    ObjectMapper objectMapper = new ObjectMapper();
    SceneExecutionServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new SceneExecutionServiceImpl(authorization, scenes, executions, behaviorEventRecorder, manualOverrideService,
                new com.hesta.backend.service.execution.CommandBatchRunner(commands, behaviorEventRecorder));
    }

    private final UUID userId = UUID.randomUUID();
    private final UUID homeId = UUID.randomUUID();
    private final UUID sceneId = UUID.randomUUID();

    @Test
    void executesActionsInOrderAndStoresPerActionResults() {
        Device device = Device.builder().id(UUID.randomUUID()).build();
        Scene scene = Scene.builder().id(sceneId).home(Home.builder().id(homeId).build()).enabled(true)
                .actions(new ArrayList<>()).build();
        scene.getActions().add(SceneAction.builder().scene(scene).targetDevice(device)
                .action("SET_SPEED").value(objectMapper.valueToTree(60)).order(1).build());
        scene.getActions().add(SceneAction.builder().scene(scene).targetDevice(device)
                .action("TURN_ON").order(0).build());
        when(scenes.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(commands.sendCommand(device.getId(), "TURN_ON", Map.of(), StateChangeSource.SCENE))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(commands.sendCommand(device.getId(), "SET_SPEED", Map.of("speed", 60, "fan", 60), StateChangeSource.SCENE))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(false).status("FAILED").build()));
        when(executions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.execute(userId, homeId, sceneId);

        assertThat(result.getStatus()).isEqualTo("PARTIAL");
        assertThat(result.getResultDetail()).hasSize(2);
        assertThat(result.getResultDetail().get(0).get("action").asText()).isEqualTo("TURN_ON");
        assertThat(result.getResultDetail().get(1).get("success").asBoolean()).isFalse();
        ArgumentCaptor<SceneExecution> saved = ArgumentCaptor.forClass(SceneExecution.class);
        verify(executions).save(saved.capture());
        assertThat(saved.getValue().getTriggerSource()).isEqualTo("MANUAL");
        var order = inOrder(commands);
        order.verify(commands).sendCommand(eq(device.getId()), eq("TURN_ON"), any(), eq(StateChangeSource.SCENE));
        order.verify(commands).sendCommand(eq(device.getId()), eq("SET_SPEED"), any(), eq(StateChangeSource.SCENE));
    }

    @Test
    void asyncRunStartsPendingThenPushesProgressAndFinalResult() {
        Device device = Device.builder().id(UUID.randomUUID()).build();
        Scene scene = Scene.builder().id(sceneId).home(Home.builder().id(homeId).build()).enabled(true)
                .actions(new ArrayList<>()).build();
        scene.getActions().add(SceneAction.builder().scene(scene).targetDevice(device).action("TURN_ON").order(0).build());
        when(scenes.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(commands.sendCommand(device.getId(), "TURN_ON", Map.of(), StateChangeSource.SCENE))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("SUCCESS").build()));
        UUID executionId = UUID.randomUUID();
        SceneExecution[] stored = new SceneExecution[1];
        when(executions.save(any())).thenAnswer(invocation -> {
            SceneExecution execution = invocation.getArgument(0);
            assertThat(execution.getCompletedAt()).as("completed_at is NOT NULL in the DB").isNotNull();
            if (execution.getId() == null) execution.setId(executionId);
            return stored[0] = execution;
        });
        when(executions.findById(executionId)).thenAnswer(invocation -> Optional.of(stored[0]));
        var publisher = mock(com.hesta.backend.realtime.publisher.RealtimeEventPublisher.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "realtimeEventPublisher", publisher);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sceneRunner",
                (java.util.concurrent.Executor) Runnable::run);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "transactionManager",
                mock(org.springframework.transaction.PlatformTransactionManager.class));

        service.executeAsync(userId, homeId, sceneId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.hesta.backend.realtime.model.RealtimeEvent<?>> events =
                ArgumentCaptor.forClass(com.hesta.backend.realtime.model.RealtimeEvent.class);
        verify(publisher, times(2)).publish(events.capture());
        var progress = (com.hesta.backend.dto.response.SceneExecutionResponse) events.getAllValues().get(0).data();
        var finalResult = (com.hesta.backend.dto.response.SceneExecutionResponse) events.getAllValues().get(1).data();
        assertThat(progress.getStatus()).isEqualTo("PENDING");
        assertThat(finalResult.getStatus()).isEqualTo("SUCCESS");
        assertThat(finalResult.getId()).isEqualTo(executionId);
        assertThat(events.getAllValues()).allMatch(event ->
                event.type() == com.hesta.backend.realtime.model.RealtimeEventType.SCENE_EXECUTION_UPDATED
                        && event.homeId().equals(homeId));
    }

    @Test
    void refusesDisabledSceneWithoutSendingCommands() {
        Scene scene = Scene.builder().id(sceneId).enabled(false).build();
        when(scenes.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        assertThatThrownBy(() -> service.execute(userId, homeId, sceneId))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(commands, executions);
    }

    @Test
    void manualOverrideSkipsScheduledSceneAction() {
        Device device = Device.builder().id(UUID.randomUUID()).build();
        Scene scene = Scene.builder().id(sceneId).home(Home.builder().id(homeId).build()).enabled(true).build();
        scene.getActions().add(SceneAction.builder().scene(scene).targetDevice(device)
                .action("TURN_ON").order(0).build());
        when(scenes.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(manualOverrideService.isActive(device.getId())).thenReturn(true);
        when(executions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.executeScheduled(homeId, sceneId);
        assertThat(result.getStatus()).isEqualTo("SKIPPED");
        assertThat(result.getResultDetail().get(0).get("status").asText()).isEqualTo("SKIPPED_OVERRIDE");
        verifyNoInteractions(commands);
    }

    @Test
    void manualSceneExecutionTakesPriorityOverPreviousOverride() {
        Device device = Device.builder().id(UUID.randomUUID()).build();
        Scene scene = Scene.builder().id(sceneId).home(Home.builder().id(homeId).build()).enabled(true).build();
        scene.getActions().add(SceneAction.builder().scene(scene).targetDevice(device)
                .action("TURN_ON").order(0).build());
        when(scenes.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(commands.sendCommand(device.getId(), "TURN_ON", Map.of(), StateChangeSource.SCENE))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(executions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.execute(userId, homeId, sceneId);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        verifyNoInteractions(manualOverrideService);
    }

    @Test
    void executesAirConditionerAndGateActionsSuccessfully() {
        Device acDevice = Device.builder().id(UUID.randomUUID()).build();
        Device gateDevice = Device.builder().id(UUID.randomUUID()).build();

        Scene scene = Scene.builder().id(sceneId).home(Home.builder().id(homeId).build()).enabled(true)
                .actions(new ArrayList<>()).build();
        scene.getActions().add(SceneAction.builder().scene(scene).targetDevice(gateDevice)
                .action("OPEN").value(null).order(0).build());
        scene.getActions().add(SceneAction.builder().scene(scene).targetDevice(acDevice)
                .action("SET_TEMPERATURE").value(objectMapper.valueToTree(24)).order(1).build());
        scene.getActions().add(SceneAction.builder().scene(scene).targetDevice(acDevice)
                .action("SET_SWING").value(objectMapper.valueToTree(true)).order(2).build());

        when(scenes.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(commands.sendCommand(eq(gateDevice.getId()), eq("OPEN"), anyMap(), eq(StateChangeSource.SCENE)))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(commands.sendCommand(eq(acDevice.getId()), eq("SET_TEMPERATURE"), anyMap(), eq(StateChangeSource.SCENE)))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(commands.sendCommand(eq(acDevice.getId()), eq("SET_SWING"), anyMap(), eq(StateChangeSource.SCENE)))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(executions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.execute(userId, homeId, sceneId);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getResultDetail()).hasSize(3);
        verify(commands).sendCommand(eq(gateDevice.getId()), eq("OPEN"), eq(Map.of()), eq(StateChangeSource.SCENE));
        verify(commands).sendCommand(eq(acDevice.getId()), eq("SET_TEMPERATURE"), argThat(map -> Integer.valueOf(24).equals(map.get("temperature"))), eq(StateChangeSource.SCENE));
        verify(commands).sendCommand(eq(acDevice.getId()), eq("SET_SWING"), argThat(map -> Boolean.TRUE.equals(map.get("swing"))), eq(StateChangeSource.SCENE));
    }
}
