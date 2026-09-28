package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface DeviceCommandService {

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
