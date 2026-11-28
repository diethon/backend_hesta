package com.hesta.backend.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hesta.backend.cache.HestaCacheService;
import com.hesta.backend.dto.request.RoomCreateRequest;
import com.hesta.backend.dto.request.RoomUpdateRequest;
import com.hesta.backend.dto.response.RoomResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.event.MemberAccessChangedEvent;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.repository.TwinLayoutRepository;
import com.hesta.backend.repository.TwinRoomLayoutRepository;
import com.hesta.backend.entity.TwinRoomLayout;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import com.hesta.backend.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomServiceImpl implements RoomService {

    private static final String ROOMS_HOME_NAMESPACE = "rooms:home";
    private static final String DEVICES_HOME_NAMESPACE = "devices:home";
    private static final Duration ROOMS_CACHE_TTL = Duration.ofSeconds(30);

    private final RoomRepository roomRepository;
    private final DeviceRepository deviceRepository;
    private final HomeAuthorizationService homeAuthorizationService;
    private final TwinLayoutRepository twinLayoutRepository;
    private final TwinRoomLayoutRepository twinRoomLayoutRepository;

    @Autowired(required = false)
    private HestaCacheService cacheService;

    public void setCacheService(HestaCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomResponse> getRoomsByHome(UUID userId, UUID homeId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext ctx = homeAuthorizationService.loadAccessContext(homeId, userId);
        boolean isCustomMember = ctx != null && !ctx.isOwner() && ctx.scope() == AccessScope.CUSTOM;
        String scopeKey = isCustomMember ? ("USER:" + userId) : "ALL";
        String cacheKey = homeId + ":" + scopeKey;

        if (cacheService != null && cacheService.isReadThroughCacheEnabled()) {
            Optional<List<RoomResponse>> cached = cacheService.get(
                    ROOMS_HOME_NAMESPACE,
                    cacheKey,
                    new TypeReference<List<RoomResponse>>() {}
            );
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        List<Room> rooms = roomRepository.findByHomeIdOrderByCreatedAtAsc(homeId);
        if (rooms.isEmpty()) {
            if (cacheService != null && cacheService.isReadThroughCacheEnabled()) {
                cacheService.put(ROOMS_HOME_NAMESPACE, cacheKey, Collections.emptyList(), ROOMS_CACHE_TTL);
            }
            return Collections.emptyList();
        }

        // Đếm số lượng thiết bị mà user thực sự có quyền xem trong mỗi phòng để tránh N+1
        List<Device> allDevices = deviceRepository.findByHomeId(homeId);
        Map<UUID, Long> deviceCountByRoom = allDevices.stream()
                .filter(d -> !d.isDeleted() && d.getRoom() != null)
                .filter(d -> !isCustomMember || homeAuthorizationService.effectiveLevel(ctx, d).canView())
                .collect(Collectors.groupingBy(d -> d.getRoom().getId(), Collectors.counting()));

        Map<UUID, Integer> floorByRoom = floorsByRoom(homeId);
        List<RoomResponse> result = rooms.stream()
                .filter(room -> {
                    if (!isCustomMember) return true;
                    AccessLevel roomLevel = ctx.roomLevels().getOrDefault(room.getId(), AccessLevel.NONE);
                    boolean hasVisibleDeviceOverride = deviceCountByRoom.getOrDefault(room.getId(), 0L) > 0L;
                    return roomLevel.canView() || hasVisibleDeviceOverride;
                })
                .map(room -> {
                    RoomResponse response = RoomResponse.fromEntity(room, deviceCountByRoom.getOrDefault(room.getId(), 0L).intValue());
                    response.setFloor(floorByRoom.get(room.getId()));
                    return response;
                })
                .collect(Collectors.toList());

        if (cacheService != null && cacheService.isReadThroughCacheEnabled()) {
            cacheService.put(ROOMS_HOME_NAMESPACE, cacheKey, result, ROOMS_CACHE_TTL);
        }
        return result;
    }

    private Map<UUID, Integer> floorsByRoom(UUID homeId) {
        return twinLayoutRepository.findByHomeId(homeId)
                .map(layout -> twinRoomLayoutRepository.findByLayoutIdOrderByRoomId(layout.getId()).stream()
                        .collect(Collectors.toMap(TwinRoomLayout::getRoomId, item -> (int) item.getFloor(), (a, b) -> a)))
                .orElse(Map.of());
    }

    @Override
    @Transactional(readOnly = true)
    public RoomResponse getRoomDetail(UUID userId, UUID homeId, UUID roomId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext ctx = homeAuthorizationService.loadAccessContext(homeId, userId);
        boolean isCustomMember = ctx != null && !ctx.isOwner() && ctx.scope() == AccessScope.CUSTOM;

        Room room = roomRepository.findByIdAndHomeId(roomId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.ROOM_NOT_FOUND));

        List<Device> visibleDevices = deviceRepository.findByRoomId(roomId).stream()
                .filter(d -> !d.isDeleted())
                .filter(d -> !isCustomMember || homeAuthorizationService.effectiveLevel(ctx, d).canView())
                .collect(Collectors.toList());

        if (isCustomMember) {
            AccessLevel roomLevel = ctx.roomLevels().getOrDefault(room.getId(), AccessLevel.NONE);
            if (!roomLevel.canView() && visibleDevices.isEmpty()) {
                throw new AppException(ErrorCode.ROOM_NOT_FOUND);
            }
        }

        return RoomResponse.fromEntity(room, visibleDevices.size());
    }

    @Override
    @Transactional
    public RoomResponse createRoom(UUID userId, UUID homeId, RoomCreateRequest request) {
        Home home = homeAuthorizationService.requireSceneManagement(userId, homeId);

        String trimmedName = request.getName().trim();
        if (roomRepository.existsByHomeIdAndNameIgnoreCase(homeId, trimmedName)) {
            throw new AppException(ErrorCode.ROOM_NAME_ALREADY_EXISTS);
        }

        Room room = Room.builder()
                .home(home)
                .name(trimmedName)
                .icon(request.getIcon() != null && !request.getIcon().isBlank() ? request.getIcon().trim() : "default")
                .layoutX(request.getLayoutX())
                .layoutY(request.getLayoutY())
                .build();

        Room saved = roomRepository.save(room);
        evictRoomCaches(homeId, false);
        log.info("User {} created new room '{}' (id: {}) in home {}", userId, saved.getName(), saved.getId(), homeId);
        return RoomResponse.fromEntity(saved, 0);
    }

    @Override
    @Transactional
    public RoomResponse updateRoom(UUID userId, UUID homeId, UUID roomId, RoomUpdateRequest request) {
        homeAuthorizationService.requireSceneManagement(userId, homeId);
        Room room = roomRepository.findByIdAndHomeId(roomId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.ROOM_NOT_FOUND));

        String trimmedName = request.getName().trim();
        if (roomRepository.existsByHomeIdAndNameIgnoreCaseAndIdNot(homeId, trimmedName, roomId)) {
            throw new AppException(ErrorCode.ROOM_NAME_ALREADY_EXISTS);
        }

        room.setName(trimmedName);
        if (request.getIcon() != null && !request.getIcon().isBlank()) {
            room.setIcon(request.getIcon().trim());
        }
        if (request.getLayoutX() != null) {
            room.setLayoutX(request.getLayoutX());
        }
        if (request.getLayoutY() != null) {
            room.setLayoutY(request.getLayoutY());
        }

        Room updated = roomRepository.save(room);
        List<Device> devices = deviceRepository.findByRoomId(roomId);
        evictRoomCaches(homeId, true);
        log.info("User {} updated room '{}' (id: {}) in home {}", userId, updated.getName(), updated.getId(), homeId);
        return RoomResponse.fromEntity(updated, devices.size());
    }

    @Override
    @Transactional
    public void deleteRoom(UUID userId, UUID homeId, UUID roomId) {
        homeAuthorizationService.requireSceneManagement(userId, homeId);
        Room room = roomRepository.findByIdAndHomeId(roomId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.ROOM_NOT_FOUND));

        // CHÍNH SÁCH AN TOÀN (SAFE DELETE): Không xóa thiết bị vật lý!
        // Chuyển toàn bộ thiết bị trong phòng sang trạng thái Unassigned (room = null)
        List<Device> devicesInRoom = deviceRepository.findByRoomId(roomId);
        if (!devicesInRoom.isEmpty()) {
            for (Device device : devicesInRoom) {
                device.setRoom(null);
            }
            deviceRepository.saveAll(devicesInRoom);
            log.info("Unassigned {} devices from room {} before deletion", devicesInRoom.size(), roomId);
        }

        roomRepository.delete(room);
        evictRoomCaches(homeId, true);
        log.info("User {} deleted room '{}' (id: {}) in home {}", userId, room.getName(), roomId, homeId);
    }

    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void onMemberAccessChanged(MemberAccessChangedEvent event) {
        if (event != null && event.homeId() != null) {
            evictRoomCaches(event.homeId(), false);
        }
    }

    private void evictRoomCaches(UUID homeId, boolean includeDevices) {
        if (cacheService == null || homeId == null) {
            return;
        }
        String prefix = homeId + ":";
        cacheService.evictByPrefix(ROOMS_HOME_NAMESPACE, prefix);
        if (includeDevices) {
            cacheService.evictByPrefix(DEVICES_HOME_NAMESPACE, prefix);
        }
    }
}
