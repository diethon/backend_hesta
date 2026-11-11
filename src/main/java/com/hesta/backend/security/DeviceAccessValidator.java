package com.hesta.backend.security;

import com.hesta.backend.entity.Room;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("deviceAccessValidator")
public class DeviceAccessValidator {

    private final HomeAuthorizationService homeAuthorizationService;
    private final RoomRepository roomRepository;
    private final DeviceRepository deviceRepository;

    @Autowired
    public DeviceAccessValidator(
            HomeAuthorizationService homeAuthorizationService,
            RoomRepository roomRepository,
            @Autowired(required = false) DeviceRepository deviceRepository) {
        this.homeAuthorizationService = homeAuthorizationService;
        this.roomRepository = roomRepository;
        this.deviceRepository = deviceRepository;
    }

    public DeviceAccessValidator(DeviceRepository deviceRepository, RoomRepository roomRepository) {
        this(null, roomRepository, deviceRepository);
    }

    public boolean canAccessDevice(UUID userId, UUID deviceId) {
        if (userId == null || deviceId == null) return false;
        if (homeAuthorizationService != null) {
            try {
                homeAuthorizationService.requireDeviceAccess(userId, deviceId, AccessLevel.VIEW);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return deviceRepository != null && deviceRepository.hasAccessToDevice(deviceId, userId);
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

    public boolean canControlDevice(UUID userId, UUID deviceId) {
        if (userId == null || deviceId == null) return false;
        if (homeAuthorizationService != null) {
            try {
                homeAuthorizationService.requireDeviceAccess(userId, deviceId, AccessLevel.CONTROL);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return canAccessDevice(userId, deviceId);
    }

    public boolean canControlDevice(Object principal, UUID deviceId) {
        if (principal instanceof CustomUserDetails userDetails) {
            return canControlDevice(userDetails.getId(), deviceId);
        }
        if (principal instanceof UUID userId) {
            return canControlDevice(userId, deviceId);
        }
        return false;
    }

    public boolean canAccessRoom(UUID userId, UUID roomId) {
        if (userId == null || roomId == null) return false;
        if (homeAuthorizationService != null) {
            try {
                Room room = roomRepository.findById(roomId).orElse(null);
                if (room == null || room.getHome() == null) return false;
                homeAuthorizationService.requireAccess(userId, room.getHome().getId());
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return roomRepository != null && roomRepository.hasAccessToRoom(roomId, userId);
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

    public boolean canControlRoom(UUID userId, UUID roomId) {
        if (userId == null || roomId == null) return false;
        if (homeAuthorizationService != null) {
            try {
                Room room = roomRepository.findById(roomId).orElse(null);
                if (room == null || room.getHome() == null) return false;
                com.hesta.backend.service.MemberAccessContext ctx = homeAuthorizationService.loadAccessContext(room.getHome().getId(), userId);
                if (!ctx.isActive()) return false;
                if (ctx.isOwner() || ctx.scope() == com.hesta.backend.enums.AccessScope.ALL) return true;
                AccessLevel lvl = ctx.roomLevels().getOrDefault(roomId, AccessLevel.NONE);
                return lvl == AccessLevel.CONTROL;
            } catch (Exception e) {
                return false;
            }
        }
        return canAccessRoom(userId, roomId);
    }

    public boolean canControlRoom(Object principal, UUID roomId) {
        if (principal instanceof CustomUserDetails userDetails) {
            return canControlRoom(userDetails.getId(), roomId);
        }
        if (principal instanceof UUID userId) {
            return canControlRoom(userId, roomId);
        }
        return false;
    }
}
