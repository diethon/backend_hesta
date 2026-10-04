package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.GateCommandRequest;
import com.hesta.backend.dto.response.GateStateResponse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface GateService {

    CompletableFuture<CommandResult> sendCommand(UUID userId, UUID deviceId, GateCommandRequest request);

    CompletableFuture<CommandResult> open(UUID userId, UUID deviceId, String nodeId);

    default CompletableFuture<CommandResult> open(UUID userId, UUID deviceId) {
        return open(userId, deviceId, null);
    }

    CompletableFuture<CommandResult> close(UUID userId, UUID deviceId, String nodeId);

    default CompletableFuture<CommandResult> close(UUID userId, UUID deviceId) {
        return close(userId, deviceId, null);
    }

    CompletableFuture<CommandResult> stop(UUID userId, UUID deviceId, String nodeId);

    default CompletableFuture<CommandResult> stop(UUID userId, UUID deviceId) {
        return stop(userId, deviceId, null);
    }

    GateStateResponse getState(UUID userId, UUID deviceId);
}
