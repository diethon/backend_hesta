package com.hesta.backend.security;

import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component("deviceAccessValidator")
@RequiredArgsConstructor
public class DeviceAccessValidator {

    private final DeviceRepository deviceRepository;
    private final RoomRepository roomRepository;

    public boolean canAccessDevice(UUID userId, UUID deviceId) {
        if (userId == null || deviceId == null) return false;
        return deviceRepository.hasAccessToDevice(deviceId, userId);
    }

    public boolean canAccessDevice(Object principal, UUID deviceId) {
        if (principal instanceof CustomUserDetails userDetails) {
            return canAccessDevice(userDetails.getId(), deviceId);
        }
        if (principal instanceof UUID userId) {
            return canAccessDevice(userId, deviceId);
        }
        return false;
    }

    public boolean canAccessRoom(UUID userId, UUID roomId) {
        if (userId == null || roomId == null) return false;
        return roomRepository.hasAccessToRoom(roomId, userId);
    }

    public boolean canAccessRoom(Object principal, UUID roomId) {
        if (principal instanceof CustomUserDetails userDetails) {
            return canAccessRoom(userDetails.getId(), roomId);
        }
        if (principal instanceof UUID userId) {
            return canAccessRoom(userId, roomId);
        }
        return false;
    }
}
