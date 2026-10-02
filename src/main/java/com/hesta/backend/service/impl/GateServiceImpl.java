package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.GateCommandRequest;
import com.hesta.backend.dto.response.GateStateResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.GateService;
import com.hesta.backend.service.HomeAuthorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class GateServiceImpl implements GateService {

    private static final Set<String> VALID_ACTIONS = Set.of(
            "OPEN", "CLOSE", "STOP",
            "GATE_OPEN", "GATE_CLOSE", "GATE_STOP"
    );

    private final DeviceRepository deviceRepository;
    private final HomeAuthorizationService homeAuthorizationService;
    private final DeviceCommandService deviceCommandService;

    public GateServiceImpl(
            DeviceRepository deviceRepository,
            HomeAuthorizationService homeAuthorizationService,
            @Qualifier("mqttDeviceCommandServiceImpl") DeviceCommandService deviceCommandService) {
        this.deviceRepository = deviceRepository;
        this.homeAuthorizationService = homeAuthorizationService;
        this.deviceCommandService = deviceCommandService;
    }

    @Override
    public CompletableFuture<CommandResult> sendCommand(UUID userId, UUID deviceId, GateCommandRequest request) {
        validateRequest(request);
        Device device = authorizeDevice(userId, deviceId);

        String action = normalizeAction(request.getAction());
        Map<String, Object> parameters = request.toParameters();

        log.info("Sending Gate command [{}] to device [{}] by user [{}]", action, deviceId, userId);
        return deviceCommandService.sendCommand(
                device != null ? device.getId() : deviceId,
                action,
                parameters,
                StateChangeSource.MANUAL
        );
    }

    @Override
    public CompletableFuture<CommandResult> open(UUID userId, UUID deviceId) {
        return sendCommand(userId, deviceId, GateCommandRequest.builder().action("OPEN").build());
    }

    @Override
    public CompletableFuture<CommandResult> close(UUID userId, UUID deviceId) {
        return sendCommand(userId, deviceId, GateCommandRequest.builder().action("CLOSE").build());
    }

    @Override
    public CompletableFuture<CommandResult> stop(UUID userId, UUID deviceId) {
        return sendCommand(userId, deviceId, GateCommandRequest.builder().action("STOP").build());
    }

    @Override
    public GateStateResponse getState(UUID userId, UUID deviceId) {
        Device device = authorizeDevice(userId, deviceId);
        if (device == null || device.getCurrentState() == null) {
            return GateStateResponse.builder()
                    .deviceId(deviceId)
                    .state("UNKNOWN")
                    .build();
        }

        Map<String, Object> current = device.getCurrentState();
        String state = current.getOrDefault("state", "UNKNOWN").toString();
        Boolean limitOpen = current.containsKey("limit_open") ? Boolean.valueOf(String.valueOf(current.get("limit_open"))) : null;
        Boolean limitClose = current.containsKey("limit_close") ? Boolean.valueOf(String.valueOf(current.get("limit_close"))) : null;

        return GateStateResponse.builder()
                .deviceId(deviceId)
                .state(state)
                .limitOpen(limitOpen)
                .limitClose(limitClose)
                .currentState(current)
                .build();
    }

    private Device authorizeDevice(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            if (userId != null) {
                throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
            }
            log.warn("Device {} not found in database during direct/unauthenticated gate request", deviceId);
            return null;
        }

        if (userId != null && device.getHome() != null) {
            homeAuthorizationService.requireAccess(userId, device.getHome().getId());
        }
        return device;
    }

    private void validateRequest(GateCommandRequest request) {
        if (request == null || request.getAction() == null || request.getAction().isBlank()) {
            throw new AppException(ErrorCode.INVALID_DEVICE_ACTION);
        }

        String rawAction = request.getAction().trim().toUpperCase();
        if (!VALID_ACTIONS.contains(rawAction)) {
            throw new AppException(ErrorCode.GATE_ACTION_INVALID);
        }
    }

    private String normalizeAction(String action) {
        String act = action.trim().toUpperCase();
        switch (act) {
            case "GATE_OPEN":
            case "OPEN":
                return "OPEN";
            case "GATE_CLOSE":
            case "CLOSE":
                return "CLOSE";
            case "GATE_STOP":
            case "STOP":
                return "STOP";
            default:
                return act;
        }
    }
}
