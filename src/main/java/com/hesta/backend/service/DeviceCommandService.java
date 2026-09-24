package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.dto.request.LedCommandRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction for sending commands to devices.
 * Used by Scene, Gesture, Voice/NLU, and Automation engines.
 * Implementations will handle the actual MQTT publishing or other protocols.
 */
public interface DeviceCommandService {

    /**
     * Send a general command to a specific device via MQTT.
     *
     * @param deviceId Target device UUID
     * @param request  Command payload with action, RGB, brightness, etc.
     */
    void sendCommand(UUID deviceId, LedCommandRequest request);
    
    /**
     * Send a command to a specific device.
     * 
     * @param deviceId   Target device UUID
     * @param action     The action to perform (TURN_ON, SET_BRIGHTNESS, etc.)
     * @param parameters Additional parameters for the action (e.g., {"level": 80})
     * @param source     The context/source triggering this command
     * @return CompletableFuture containing the CommandResult
     */
    CompletableFuture<CommandResult> sendCommand(
            UUID deviceId, 
            DeviceAction action, 
            Map<String, Object> parameters, 
            StateChangeSource source
    );
}
