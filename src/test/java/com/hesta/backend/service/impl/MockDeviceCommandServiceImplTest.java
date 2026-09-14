package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class MockDeviceCommandServiceImplTest {

    private MockDeviceCommandServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MockDeviceCommandServiceImpl();
    }

    @Test
    void testSendCommand_Success_TurnOn() throws ExecutionException, InterruptedException {
        UUID deviceId = UUID.randomUUID();
        
        CommandResult result = service.sendCommand(deviceId, DeviceAction.TURN_ON, null, StateChangeSource.MANUAL).get();
        
        assertTrue(result.isSuccess());
        assertEquals("ACKNOWLEDGED", result.getStatus());
        assertNotNull(result.getCommandId());
        assertNotNull(result.getAcknowledgedState());
        assertEquals("ON", result.getAcknowledgedState().get("power"));
    }

    @Test
    void testSendCommand_Failure_MissingParameters() throws ExecutionException, InterruptedException {
        UUID deviceId = UUID.randomUUID();
        
        CommandResult result = service.sendCommand(deviceId, DeviceAction.SET_MODE, new HashMap<>(), StateChangeSource.SCENE).get();
        
        assertFalse(result.isSuccess());
        assertEquals("FAILED", result.getStatus());
        assertEquals("MISSING_PARAMETERS", result.getErrorCode());
    }
    
    @Test
    void testSendCommand_Failure_NullAction() throws ExecutionException, InterruptedException {
        UUID deviceId = UUID.randomUUID();
        
        CommandResult result = service.sendCommand(deviceId, null, null, StateChangeSource.MANUAL).get();
        
        assertFalse(result.isSuccess());
        assertEquals("FAILED", result.getStatus());
        assertEquals("INVALID_ACTION", result.getErrorCode());
    }
}
