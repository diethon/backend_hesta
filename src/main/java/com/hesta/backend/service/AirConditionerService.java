package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AirConditionerCommandRequest;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AirConditionerService {
    CompletableFuture<CommandResult> sendCommand(UUID userId, UUID deviceId, AirConditionerCommandRequest request);
    CompletableFuture<CommandResult> setPower(UUID userId, UUID deviceId, boolean power);
    CompletableFuture<CommandResult> setTemperature(UUID userId, UUID deviceId, int temperature);
    CompletableFuture<CommandResult> temperaturePlus(UUID userId, UUID deviceId);
    CompletableFuture<CommandResult> temperatureMinus(UUID userId, UUID deviceId);
    CompletableFuture<CommandResult> setFan(UUID userId, UUID deviceId, String fan);
    CompletableFuture<CommandResult> setMode(UUID userId, UUID deviceId, String mode);
    CompletableFuture<CommandResult> setSwing(UUID userId, UUID deviceId, boolean swing);
    CompletableFuture<CommandResult> setTimer(UUID userId, UUID deviceId, int hour, boolean halfHour);
    CompletableFuture<CommandResult> cancelTimer(UUID userId, UUID deviceId);
    CompletableFuture<CommandResult> getState(UUID userId, UUID deviceId);
}
