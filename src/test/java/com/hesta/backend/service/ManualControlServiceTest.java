package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.BehaviorEventRepository;
import com.hesta.backend.service.impl.ManualControlServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManualControlServiceTest {
    @Mock HomeAuthorizationService authorization;
    @Mock DeviceRepository devices;
    @Mock DeviceCommandService commands;
    @Mock ManualOverrideService overrides;
    @Mock BehaviorEventRecorder recorder;
    @Mock BehaviorEventRepository behaviorEvents;
    @InjectMocks ManualControlServiceImpl service;

    UUID userId = UUID.randomUUID();
    UUID homeId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();

    @Test
    void manualPowerCommandActivatesOverrideBeforeSending() {
        Home home = Home.builder().id(homeId).build();
        Device device = Device.builder().id(deviceId).node(EdgeNode.builder().home(home).build())
                .room(Room.builder().home(home).build())
                .capabilities(List.of("TURN_ON", "TURN_OFF")).build();
        when(devices.findByIdWithRoomHome(deviceId)).thenReturn(Optional.of(device));
        OffsetDateTime until = OffsetDateTime.now().plusMinutes(30);
        when(overrides.activate(device, userId, "OVERRIDE")).thenReturn(until);
        CommandResult command = CommandResult.builder().success(true).status("ACKNOWLEDGED").build();
        when(commands.sendCommand(deviceId, DeviceAction.TURN_ON, Map.of(), StateChangeSource.MANUAL))
                .thenReturn(CompletableFuture.completedFuture(command));

        var result = service.command(userId, deviceId, "TURN_ON");

        assertThat(result.getCommand().isSuccess()).isTrue();
        assertThat(result.getOverrideUntil()).isEqualTo(until);
        var order = inOrder(authorization, overrides, commands, recorder);
        order.verify(authorization).requireAccess(userId, homeId);
        order.verify(overrides).activate(device, userId, "OVERRIDE");
        order.verify(commands).sendCommand(deviceId, DeviceAction.TURN_ON, Map.of(), StateChangeSource.MANUAL);
        order.verify(recorder).recordCommand(device, DeviceAction.TURN_ON, StateChangeSource.MANUAL, command);
    }

    @Test
    void rejectsUnsupportedManualActionWithoutOverride() {
        Home home = Home.builder().id(homeId).build();
        Device device = Device.builder().id(deviceId).node(EdgeNode.builder().home(home).build())
                .room(Room.builder().home(home).build()).build();
        when(devices.findByIdWithRoomHome(deviceId)).thenReturn(Optional.of(device));
        assertThatThrownBy(() -> service.command(userId, deviceId, "SET_SPEED"))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(commands, overrides, recorder);
    }
}
