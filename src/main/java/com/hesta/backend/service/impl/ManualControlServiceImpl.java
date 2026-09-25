package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.response.ManualCommandResponse;
import com.hesta.backend.dto.response.ManualOverrideResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.BehaviorEventRepository;
import com.hesta.backend.service.BehaviorEventRecorder;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.ManualControlService;
import com.hesta.backend.service.ManualOverrideService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ManualControlServiceImpl implements ManualControlService {
    private final HomeAuthorizationService authorization;
    private final DeviceRepository devices;
    private final DeviceCommandService commands;
    private final ManualOverrideService overrides;
    private final BehaviorEventRecorder recorder;
    private final BehaviorEventRepository behaviorEvents;

    @Override
    public ManualCommandResponse command(UUID userId, UUID deviceId, String rawAction) {
        Device device = authorizedDevice(userId, deviceId);
        DeviceAction action;
        try {
            action = DeviceAction.valueOf(rawAction.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new AppException(ErrorCode.AUTOMATION_ACTION_INVALID);
        }
        if (action != DeviceAction.TURN_ON && action != DeviceAction.TURN_OFF) {
            throw new AppException(ErrorCode.AUTOMATION_ACTION_INVALID);
        }
        if (device.getCapabilities() != null && !device.getCapabilities().isEmpty()
                && device.getCapabilities().stream().noneMatch(cap -> cap.equalsIgnoreCase(action.name()))) {
            throw new AppException(ErrorCode.AUTOMATION_ACTION_INVALID);
        }
        OffsetDateTime until = overrides.activate(device, userId, "OVERRIDE");
        CommandResult result = commands.sendCommand(deviceId, action, Map.of(), StateChangeSource.MANUAL).join();
        if (result.isSuccess()) recorder.recordCommand(device, action, StateChangeSource.MANUAL, result);
        return ManualCommandResponse.builder().command(result).overrideUntil(until).build();
    }

    @Override
    public OffsetDateTime cancelAutomation(UUID userId, UUID deviceId) {
        return overrides.activate(authorizedDevice(userId, deviceId), userId, "CANCEL");
    }

    @Override
    public List<ManualOverrideResponse> history(UUID userId, UUID deviceId) {
        authorizedDevice(userId, deviceId);
        return behaviorEvents.findTop100ByDeviceIdAndEventTypeOrderByOccurredAtDesc(deviceId, "MANUAL_OVERRIDE")
                .stream().map(event -> ManualOverrideResponse.builder().id(event.getId())
                        .action(event.getAction()).userId(event.getUser() == null ? null : event.getUser().getId())
                        .occurredAt(event.getOccurredAt()).expiresAt(event.getOccurredAt().plusMinutes(30)).build())
                .toList();
    }

    private Device authorizedDevice(UUID userId, UUID deviceId) {
        Device device = devices.findById(deviceId).orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        authorization.requireAccess(userId, device.getNode().getHome().getId());
        return device;
    }
}
