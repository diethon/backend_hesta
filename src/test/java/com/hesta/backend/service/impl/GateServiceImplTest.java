package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.GateCommandRequest;
import com.hesta.backend.dto.response.GateStateResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.HomeAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GateServiceImplTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @Mock
    private DeviceCommandService deviceCommandService;

    private GateServiceImpl gateService;

    private UUID userId;
    private UUID deviceId;
    private UUID homeId;
    private Device device;

    @BeforeEach
    void setUp() {
        gateService = new GateServiceImpl(deviceRepository, homeAuthorizationService, deviceCommandService);
        userId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        homeId = UUID.randomUUID();

        Home home = Home.builder().id(homeId).build();
        Room room = Room.builder().id(UUID.randomUUID()).home(home).build();
        device = Device.builder()
                .id(deviceId)
                .name("Smart Sliding Gate")
                .deviceType("GATE")
                .room(room)
                .currentState(new HashMap<>(Map.of("state", "CLOSED")))
                .build();
    }

    @Test
    void open_Success() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        CommandResult expectedResult = CommandResult.builder().commandId("cmd-1").success(true).status("SUCCESS").build();
        when(deviceCommandService.sendCommand(eq(deviceId), eq("OPEN"), anyMap(), eq(StateChangeSource.MANUAL)))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

        CompletableFuture<CommandResult> future = gateService.open(userId, deviceId);
        CommandResult result = future.join();

        assertNotNull(result);
        assertTrue(result.isSuccess());
        verify(homeAuthorizationService).requireAccess(userId, homeId);
        verify(deviceCommandService).sendCommand(eq(deviceId), eq("OPEN"), anyMap(), eq(StateChangeSource.MANUAL));
    }

    @Test
    void close_Success() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        CommandResult expectedResult = CommandResult.builder().commandId("cmd-2").success(true).status("SUCCESS").build();
        when(deviceCommandService.sendCommand(eq(deviceId), eq("CLOSE"), anyMap(), eq(StateChangeSource.MANUAL)))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

        CompletableFuture<CommandResult> future = gateService.close(userId, deviceId);
        CommandResult result = future.join();

        assertNotNull(result);
        assertTrue(result.isSuccess());
        verify(deviceCommandService).sendCommand(eq(deviceId), eq("CLOSE"), anyMap(), eq(StateChangeSource.MANUAL));
    }

    @Test
    void stop_Success() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        CommandResult expectedResult = CommandResult.builder().commandId("cmd-3").success(true).status("SUCCESS").build();
        when(deviceCommandService.sendCommand(eq(deviceId), eq("STOP"), anyMap(), eq(StateChangeSource.MANUAL)))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

        CompletableFuture<CommandResult> future = gateService.stop(userId, deviceId);
        CommandResult result = future.join();

        assertNotNull(result);
        assertTrue(result.isSuccess());
        verify(deviceCommandService).sendCommand(eq(deviceId), eq("STOP"), anyMap(), eq(StateChangeSource.MANUAL));
    }

    @Test
    void sendCommand_WithNodeId_PassesParameters() {
        String testNodeId = UUID.randomUUID().toString();
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        CommandResult expectedResult = CommandResult.builder().commandId("cmd-4").success(true).status("SUCCESS").build();
        when(deviceCommandService.sendCommand(eq(deviceId), eq("OPEN"), argThat(p -> testNodeId.equals(p.get("nodeId"))), eq(StateChangeSource.MANUAL)))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

        GateCommandRequest request = GateCommandRequest.builder()
                .action("OPEN")
                .nodeId(testNodeId)
                .build();

        CompletableFuture<CommandResult> future = gateService.sendCommand(userId, deviceId, request);
        CommandResult result = future.join();

        assertNotNull(result);
        assertTrue(result.isSuccess());
    }

    @Test
    void sendCommand_InvalidAction_ThrowsException() {
        GateCommandRequest request = GateCommandRequest.builder()
                .action("FLY")
                .build();

        AppException ex = assertThrows(AppException.class, () -> gateService.sendCommand(userId, deviceId, request));
        assertEquals(ErrorCode.GATE_ACTION_INVALID, ex.getErrorCode());
        verifyNoInteractions(deviceCommandService);
    }

    @Test
    void getState_ReturnsState() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        GateStateResponse response = gateService.getState(userId, deviceId);

        assertNotNull(response);
        assertEquals(deviceId, response.getDeviceId());
        assertEquals("CLOSED", response.getState());
        assertEquals(Map.of("state", "CLOSED"), response.getCurrentState());
    }
}
