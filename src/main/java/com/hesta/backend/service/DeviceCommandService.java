package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.dto.request.LedCommandRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface DeviceCommandService {

    /**
     * Send a general command to a specific device via MQTT.
     *
     * @param deviceId Target device UUID
     * @param request  Command payload with action, RGB, brightness, etc.
     */
    void sendCommand(UUID deviceId, LedCommandRequest request);
    
    CompletableFuture<CommandResult> sendCommand(
            UUID deviceId, 
            DeviceAction action, 
            Map<String, Object> parameters, 
            StateChangeSource source
    );

    CompletableFuture<java.util.List<CommandResult>> sendRoomCommand(
            UUID roomId, 
            DeviceAction action, 
            Map<String, Object> parameters, 
            StateChangeSource source
    );
}
