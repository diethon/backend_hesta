package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.DeviceUpdateRequest;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.entity.Room;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;
    private final HomeMemberRepository homeMemberRepository;
    private final RoomRepository roomRepository;
    private final com.hesta.backend.repository.DeviceStateHistoryRepository deviceStateHistoryRepository;

    private void checkHomeAccess(UUID userId, UUID homeId) {
        homeMemberRepository.findByHomeIdAndUserId(homeId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
    }

    private void checkHomeOwner(UUID userId, UUID homeId) {
        HomeMember member = homeMemberRepository.findByHomeIdAndUserId(homeId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        if (member.getRole() != HomeRole.OWNER) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceResponse> getDevicesByHome(UUID userId, UUID homeId) {
        checkHomeAccess(userId, homeId);
        return deviceRepository.findByHomeId(homeId).stream()
                .map(DeviceResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceResponse> getDevicesByRoom(UUID userId, UUID roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Room not found"));
        checkHomeAccess(userId, room.getHome().getId());

        return deviceRepository.findByRoomId(roomId).stream()
                .map(DeviceResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceResponse getDeviceDetail(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        checkHomeAccess(userId, device.getHome().getId());
        return DeviceResponse.fromEntity(device);
    }

    @Override
    @Transactional
    public void updateDeviceStateFromMqtt(String deviceIdStr, java.util.Map<String, Object> payload) {
        try {
            UUID deviceId = UUID.fromString(deviceIdStr);
            Device device = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> new RuntimeException("Device not found"));

            java.util.List<String> allowedKeys = device.getCapabilities();
            java.util.Map<String, Object> newState = new java.util.HashMap<>();

            if (allowedKeys == null || allowedKeys.isEmpty()) {
                newState.putAll(payload);
            } else {
                for (java.util.Map.Entry<String, Object> entry : payload.entrySet()) {
                    if (allowedKeys.contains(entry.getKey())) {
                        newState.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            device.setCurrentState(newState);
            deviceRepository.save(device);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid payload or UUID format: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public DeviceResponse updateDeviceConfig(UUID userId, UUID deviceId, DeviceUpdateRequest request) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));

        checkHomeOwner(userId, device.getHome().getId());

        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            device.setName(request.getName().trim());
        }

        if (request.getRoomId() != null) {
            Room room = roomRepository.findById(request.getRoomId())
                    .orElseThrow(() -> new RuntimeException("Room not found"));
            if (!room.getHome().getId().equals(device.getHome().getId())) {
                throw new RuntimeException("Room does not belong to this home");
            }
            device.setRoom(room);
        }

        if (request.getIcon() != null) {
            device.setIcon(request.getIcon());
        }
        if (request.getDigitalTwinX() != null) {
            device.setDigitalTwinX(request.getDigitalTwinX());
        }
        if (request.getDigitalTwinY() != null) {
            device.setDigitalTwinY(request.getDigitalTwinY());
        }
        if (request.getDigitalTwinZ() != null) {
            device.setDigitalTwinZ(request.getDigitalTwinZ());
        }

        Device saved = deviceRepository.save(device);
        return DeviceResponse.fromEntity(saved);
    }

    @Override
    @Transactional
    public void removeDevice(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));

        checkHomeOwner(userId, device.getHome().getId());

        device.setDeleted(true);
        deviceRepository.save(device);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.hesta.backend.dto.response.DeviceStateHistoryResponse> getDeviceHistory(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        checkHomeAccess(userId, device.getHome().getId());

        return deviceStateHistoryRepository.findByDeviceIdOrderByChangedAtDesc(deviceId)
                .stream()
                .map(com.hesta.backend.dto.response.DeviceStateHistoryResponse::fromEntity)
                .collect(Collectors.toList());
    }
}