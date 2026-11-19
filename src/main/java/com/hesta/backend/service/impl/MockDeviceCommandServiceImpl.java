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
        
        if ("SET_SPEED".equalsIgnoreCase(action) || "SET_FAN".equalsIgnoreCase(action) || "SET_FAN_SPEED".equalsIgnoreCase(action)) {
            int spd = 1;
            if (parameters != null) {
                Object sObj = parameters.get("speed");
                if (sObj == null) sObj = parameters.get("fan_speed");
                if (sObj == null) sObj = parameters.get("fanSpeed");
                if (sObj == null) sObj = parameters.get("level");
                if (sObj == null) sObj = parameters.get("fan");
                if (sObj != null) {
                    String sStr = sObj.toString().trim().toUpperCase();
                    if ("1".equals(sStr) || "LOW".equals(sStr)) spd = 1;
                    else if ("2".equals(sStr) || "MED".equals(sStr) || "MEDIUM".equals(sStr)) spd = 2;
                    else if ("3".equals(sStr) || "HIGH".equals(sStr) || "MAX".equals(sStr)) spd = 3;
                    else if ("0".equals(sStr) || "OFF".equals(sStr)) spd = 0;
                    else {
                        try { spd = Math.max(0, Math.min(3, Integer.parseInt(sStr))); } catch (Exception ignored) {}
                    }
                }
            }
            ackState.put("speed", spd);
            ackState.put("fanSpeed", spd);
            ackState.put("fan", spd == 1 ? "LOW" : (spd == 2 ? "MED" : (spd == 3 ? "HIGH" : "OFF")));
            ackState.put("power", spd > 0 ? "ON" : "OFF");
        }

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