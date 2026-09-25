package com.hesta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.entity.SceneExecution;
import com.hesta.backend.enums.DeviceAction;
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
    @org.mockito.Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks SceneExecutionServiceImpl service;

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
        when(commands.sendCommand(device.getId(), DeviceAction.TURN_ON, Map.of(), StateChangeSource.SCENE))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(commands.sendCommand(device.getId(), DeviceAction.SET_SPEED, Map.of("speed", 60), StateChangeSource.SCENE))
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
        order.verify(commands).sendCommand(eq(device.getId()), eq(DeviceAction.TURN_ON), any(), eq(StateChangeSource.SCENE));
        order.verify(commands).sendCommand(eq(device.getId()), eq(DeviceAction.SET_SPEED), any(), eq(StateChangeSource.SCENE));
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
        when(commands.sendCommand(device.getId(), DeviceAction.TURN_ON, Map.of(), StateChangeSource.SCENE))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        when(executions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.execute(userId, homeId, sceneId);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        verifyNoInteractions(manualOverrideService);
    }
}
