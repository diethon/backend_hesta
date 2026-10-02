package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.GateCommandRequest;
import com.hesta.backend.dto.response.GateStateResponse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface GateService {

    CompletableFuture<CommandResult> sendCommand(UUID userId, UUID deviceId, GateCommandRequest request);

    CompletableFuture<CommandResult> open(UUID userId, UUID deviceId);

    CompletableFuture<CommandResult> close(UUID userId, UUID deviceId);

    CompletableFuture<CommandResult> stop(UUID userId, UUID deviceId);

    GateStateResponse getState(UUID userId, UUID deviceId);
}
