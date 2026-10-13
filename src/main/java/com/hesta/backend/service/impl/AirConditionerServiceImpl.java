package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AirConditionerCommandRequest;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.AirConditionerService;
import com.hesta.backend.service.DeviceCommandService;
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
public class AirConditionerServiceImpl implements AirConditionerService {

    private static final Set<String> VALID_MODES = Set.of("AUTO", "COOL", "DRY", "HEAT");
    private static final Set<String> VALID_FANS = Set.of("AUTO", "LOW", "MID", "HIGH");

    private final DeviceRepository deviceRepository;
    private final HomeAuthorizationService homeAuthorizationService;
    private final DeviceCommandService deviceCommandService;

    public AirConditionerServiceImpl(
            DeviceRepository deviceRepository,
            HomeAuthorizationService homeAuthorizationService,
            @Qualifier("mqttDeviceCommandServiceImpl") DeviceCommandService deviceCommandService) {
        this.deviceRepository = deviceRepository;
        this.homeAuthorizationService = homeAuthorizationService;
        this.deviceCommandService = deviceCommandService;
    }

    @Override
    public CompletableFuture<CommandResult> sendCommand(UUID userId, UUID deviceId, AirConditionerCommandRequest request) {
        Device device = authorizeDevice(userId, deviceId);
        validateRequest(request);

        String action = normalizeAction(request.getAction(), request);
        Map<String, Object> parameters = request.toParameters();

        log.info("Sending AC command [{}] to device [{}] by user [{}]", action, deviceId, userId);
        return deviceCommandService.sendCommand(device.getId(), action, parameters, StateChangeSource.MANUAL);
    }

    @Override
    public CompletableFuture<CommandResult> setPower(UUID userId, UUID deviceId, boolean power) {
        return sendCommand(userId, deviceId, AirConditionerCommandRequest.builder()
                .action("SET_POWER")
                .power(power)
                .build());
    }

    @Override
    public CompletableFuture<CommandResult> setTemperature(UUID userId, UUID deviceId, int temperature) {
        return sendCommand(userId, deviceId, AirConditionerCommandRequest.builder()
                .action("SET_TEMPERATURE")
                .temperature(temperature)
                .build());
    }

    @Override
    public CompletableFuture<CommandResult> temperaturePlus(UUID userId, UUID deviceId) {
        return sendCommand(userId, deviceId, AirConditionerCommandRequest.builder()
                .action("TEMPERATURE_PLUS")
                .build());
    }

    @Override
    public CompletableFuture<CommandResult> temperatureMinus(UUID userId, UUID deviceId) {
        return sendCommand(userId, deviceId, AirConditionerCommandRequest.builder()
                .action("TEMPERATURE_MINUS")
                .build());
    }

    @Override
    public CompletableFuture<CommandResult> setFan(UUID userId, UUID deviceId, String fan) {
        return sendCommand(userId, deviceId, AirConditionerCommandRequest.builder()
                .action("SET_FAN")
                .fan(fan)
                .build());
    }

    @Override
    public CompletableFuture<CommandResult> setMode(UUID userId, UUID deviceId, String mode) {
        return sendCommand(userId, deviceId, AirConditionerCommandRequest.builder()
                .action("SET_MODE")
                .mode(mode)
                .build());
    }

    @Override
    public CompletableFuture<CommandResult> setSwing(UUID userId, UUID deviceId, boolean swing) {
        return sendCommand(userId, deviceId, AirConditionerCommandRequest.builder()
                .action("SET_SWING")
                .swing(swing)
                .build());
    }

    @Override
    public CompletableFuture<CommandResult> setTimer(UUID userId, UUID deviceId, int hour, boolean halfHour) {
        return sendCommand(userId, deviceId, AirConditionerCommandRequest.builder()
                .action("SET_TIMER")
                .hour(hour)
                .halfHour(halfHour)
                .build());
    }

    @Override
    public CompletableFuture<CommandResult> cancelTimer(UUID userId, UUID deviceId) {
        return sendCommand(userId, deviceId, AirConditionerCommandRequest.builder()
                .action("CANCEL_TIMER")
                .build());
    }

    @Override
    public CompletableFuture<CommandResult> getState(UUID userId, UUID deviceId) {
        return sendCommand(userId, deviceId, AirConditionerCommandRequest.builder()
                .action("GET_STATE")
                .build());
    }

    private Device authorizeDevice(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        if (userId != null && device.getHome() != null) {
            homeAuthorizationService.requireAccess(userId, device.getHome().getId());
        }
        return device;
    }

    private void validateRequest(AirConditionerCommandRequest request) {
        if (request == null || request.getAction() == null || request.getAction().isBlank()) {
            throw new AppException(ErrorCode.INVALID_DEVICE_ACTION);
        }

        String rawAction = request.getAction().trim().toUpperCase();

        if ("SET_TEMPERATURE".equals(rawAction)) {
            if (request.getTemperature() == null || request.getTemperature() < 20 || request.getTemperature() > 30) {
                throw new AppException(ErrorCode.AC_TEMPERATURE_INVALID);
            }
        } else if ("SET_MODE".equals(rawAction)) {
            if (request.getMode() == null || !VALID_MODES.contains(request.getMode().trim().toUpperCase())) {
                throw new AppException(ErrorCode.AC_MODE_INVALID);
            }
        } else if ("SET_FAN".equals(rawAction)) {
            if (request.getFan() == null || !VALID_FANS.contains(request.getFan().trim().toUpperCase())) {
                throw new AppException(ErrorCode.AC_FAN_INVALID);
            }
        } else if ("SET_TIMER".equals(rawAction)) {
            if (request.getHour() == null || request.getHour() < 0 || request.getHour() > 24) {
                throw new AppException(ErrorCode.AC_TIMER_INVALID);
            }
        }
    }

    private String normalizeAction(String action, AirConditionerCommandRequest request) {
        String act = action.trim().toUpperCase();
        switch (act) {
            case "TURN_ON":
            case "POWER_ON":
                if (request.getPower() == null) request.setPower(true);
                return "SET_POWER";
            case "TURN_OFF":
            case "POWER_OFF":
                if (request.getPower() == null) request.setPower(false);
                return "SET_POWER";
            case "TEMP_UP":
                return "TEMPERATURE_PLUS";
            case "TEMP_DOWN":
                return "TEMPERATURE_MINUS";
            default:
                return act;
        }
    }
}
