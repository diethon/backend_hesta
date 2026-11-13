package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.service.DeviceCommandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Mock implementation of DeviceCommandService for local development and testing.
 * Returns deterministic results without actually publishing to MQTT.
 */
@Slf4j
@Service
public class MockDeviceCommandServiceImpl implements DeviceCommandService {



    @Override
    public CompletableFuture<CommandResult> sendCommand(UUID deviceId, String action, Map<String, Object> parameters, StateChangeSource source) {
        log.info("Mock sending command to Device [{}]: Action [{}], Source [{}]", deviceId, action, source);
        
        String commandId = UUID.randomUUID().toString();
        
        if (action == null) {
            return CompletableFuture.completedFuture(
                    CommandResult.builder()
                            .commandId(commandId)
                            .success(false)
                            .status("FAILED")
                            .errorCode("INVALID_ACTION")
                            .message("Action cannot be null")
                            .build()
            );
        }

        // Simulate unsupported action behavior
        if ("SET_MODE".equals(action) && (parameters == null || !parameters.containsKey("mode"))) {
            return CompletableFuture.completedFuture(
                    CommandResult.builder()
                            .commandId(commandId)
                            .success(false)
                            .status("FAILED")
                            .errorCode("MISSING_PARAMETERS")
                            .message("SET_MODE requires a 'mode' parameter")
                            .build()
            );
        }
        
        // Simulate success
        Map<String, Object> ackState = new HashMap<>();
        if (parameters != null) ackState.putAll(parameters);
        
        if ("TURN_ON".equals(action)) ackState.put("power", "ON");
        if ("TURN_OFF".equals(action)) ackState.put("power", "OFF");
        
        if ("SET_COLOR".equals(action)) {
            ackState.put("power", "ON");
            Map<String, Object> colorState = new HashMap<>();
            if (parameters != null) {
                if (parameters.containsKey("r")) colorState.put("r", parameters.get("r"));
                if (parameters.containsKey("g")) colorState.put("g", parameters.get("g"));
                if (parameters.containsKey("b")) colorState.put("b", parameters.get("b"));
            }
            ackState.put("color", colorState);
        }

        return CompletableFuture.completedFuture(
                CommandResult.builder()
                        .commandId(commandId)
                        .success(true)
                        .status("ACKNOWLEDGED")
                        .message("Mock command executed successfully")
                        .acknowledgedState(ackState)
                        .latencyMs(45L) // Mock latency
                        .build()
        );
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.List<CommandResult>> sendRoomCommand(
            java.util.UUID roomId, 
            String action, 
            java.util.Map<String, Object> parameters, 
            com.hesta.backend.enums.StateChangeSource source) {
        return java.util.concurrent.CompletableFuture.completedFuture(new java.util.ArrayList<>());
    }
}