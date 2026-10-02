package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AirConditionerCommandRequest;
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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AirConditionerServiceImplTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @Mock
    private DeviceCommandService deviceCommandService;

    private AirConditionerServiceImpl airConditionerService;

    private UUID userId;
    private UUID deviceId;
    private UUID homeId;
    private Device device;

    @BeforeEach
    void setUp() {
        airConditionerService = new AirConditionerServiceImpl(deviceRepository, homeAuthorizationService, deviceCommandService);
        userId = UUID.randomUUID();
        deviceId = UUID.fromString("05d8a4fd-d7f1-4c62-a70e-a4213552b054");
        homeId = UUID.randomUUID();

        Home home = Home.builder().id(homeId).build();
        Room room = Room.builder().id(UUID.randomUUID()).home(home).build();
        device = Device.builder()
                .id(deviceId)
                .name("Casper AC")
                .deviceType("AIR_CONDITIONER")
                .room(room)
                .build();
    }

    @Test
    void sendCommand_PowerOn_Success() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        CommandResult expectedResult = CommandResult.builder().commandId("cmd-1").success(true).status("SUCCESS").build();
        when(deviceCommandService.sendCommand(eq(deviceId), eq("SET_POWER"), anyMap(), eq(StateChangeSource.MANUAL)))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

        AirConditionerCommandRequest request = AirConditionerCommandRequest.builder()
                .action("TURN_ON")
                .build();

        CompletableFuture<CommandResult> future = airConditionerService.sendCommand(userId, deviceId, request);
        CommandResult result = future.join();

        assertNotNull(result);
        assertTrue(result.isSuccess());
        verify(homeAuthorizationService).requireAccess(userId, homeId);
        verify(deviceCommandService).sendCommand(eq(deviceId), eq("SET_POWER"), argThat(params -> Boolean.TRUE.equals(params.get("power"))), eq(StateChangeSource.MANUAL));
    }

    @Test
    void sendCommand_SetTemperature_Valid() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        CommandResult expectedResult = CommandResult.builder().commandId("cmd-2").success(true).status("SUCCESS").build();
        when(deviceCommandService.sendCommand(eq(deviceId), eq("SET_TEMPERATURE"), anyMap(), eq(StateChangeSource.MANUAL)))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

        AirConditionerCommandRequest request = AirConditionerCommandRequest.builder()
                .action("SET_TEMPERATURE")
                .temperature(25)
                .build();

        CompletableFuture<CommandResult> future = airConditionerService.sendCommand(userId, deviceId, request);
        CommandResult result = future.join();

        assertTrue(result.isSuccess());
        verify(deviceCommandService).sendCommand(eq(deviceId), eq("SET_TEMPERATURE"), argThat(params -> Integer.valueOf(25).equals(params.get("temperature"))), eq(StateChangeSource.MANUAL));
    }

    @Test
    void sendCommand_SetTemperature_InvalidRange_ThrowsException() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        AirConditionerCommandRequest request = AirConditionerCommandRequest.builder()
                .action("SET_TEMPERATURE")
                .temperature(18) // below 20
                .build();

        AppException ex = assertThrows(AppException.class, () -> airConditionerService.sendCommand(userId, deviceId, request));
        assertEquals(ErrorCode.AC_TEMPERATURE_INVALID, ex.getErrorCode());
    }

    @Test
    void sendCommand_SetMode_Valid() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(deviceCommandService.sendCommand(eq(deviceId), eq("SET_MODE"), anyMap(), eq(StateChangeSource.MANUAL)))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).build()));

        CompletableFuture<CommandResult> future = airConditionerService.setMode(userId, deviceId, "COOL");
        assertTrue(future.join().isSuccess());
    }

    @Test
    void sendCommand_SetMode_Invalid_ThrowsException() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        AirConditionerCommandRequest request = AirConditionerCommandRequest.builder()
                .action("SET_MODE")
                .mode("TURBO_INVALID")
                .build();

        AppException ex = assertThrows(AppException.class, () -> airConditionerService.sendCommand(userId, deviceId, request));
        assertEquals(ErrorCode.AC_MODE_INVALID, ex.getErrorCode());
    }

    @Test
    void sendCommand_SetFan_Valid() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(deviceCommandService.sendCommand(eq(deviceId), eq("SET_FAN"), anyMap(), eq(StateChangeSource.MANUAL)))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).build()));

        CompletableFuture<CommandResult> future = airConditionerService.setFan(userId, deviceId, "HIGH");
        assertTrue(future.join().isSuccess());
    }

    @Test
    void sendCommand_SetFan_Invalid_ThrowsException() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        AirConditionerCommandRequest request = AirConditionerCommandRequest.builder()
                .action("SET_FAN")
                .fan("SUPER_FAST")
                .build();

        AppException ex = assertThrows(AppException.class, () -> airConditionerService.sendCommand(userId, deviceId, request));
        assertEquals(ErrorCode.AC_FAN_INVALID, ex.getErrorCode());
    }

    @Test
    void sendCommand_DeviceNotFound_ThrowsException() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.empty());

        AirConditionerCommandRequest request = AirConditionerCommandRequest.builder()
                .action("GET_STATE")
                .build();

        AppException ex = assertThrows(AppException.class, () -> airConditionerService.sendCommand(userId, deviceId, request));
        assertEquals(ErrorCode.DEVICE_NOT_FOUND, ex.getErrorCode());
    }
}
