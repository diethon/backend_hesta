package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.enums.StateChangeSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface DeviceCommandService {

    CompletableFuture<CommandResult> sendCommand(
            UUID deviceId, 
            String action, 
            Map<String, Object> parameters, 
            StateChangeSource source
    );

    CompletableFuture<java.util.List<CommandResult>> sendRoomCommand(
            UUID roomId, 
            String action, 
            Map<String, Object> parameters, 
            StateChangeSource source
    );
}
