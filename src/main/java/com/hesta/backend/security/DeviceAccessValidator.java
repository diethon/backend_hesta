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
        return deviceRepository.hasAccessToDevice(deviceId, userId);
    }

    public boolean canAccessRoom(UUID userId, UUID roomId) {
        return roomRepository.hasAccessToRoom(roomId, userId);
    }
}
