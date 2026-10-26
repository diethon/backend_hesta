package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.RoomCreateRequest;
import com.hesta.backend.dto.request.RoomUpdateRequest;
import com.hesta.backend.dto.response.RoomResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomServiceImpl implements RoomService {

    private final RoomRepository roomRepository;
    private final DeviceRepository deviceRepository;
    private final HomeAuthorizationService homeAuthorizationService;

    @Override
    @Transactional(readOnly = true)
    public List<RoomResponse> getRoomsByHome(UUID userId, UUID homeId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        List<Room> rooms = roomRepository.findByHomeIdOrderByCreatedAtAsc(homeId);
        if (rooms.isEmpty()) {
            return Collections.emptyList();
        }

        // Đếm số lượng thiết bị trong mỗi phòng để tránh N+1
        List<Device> allDevices = deviceRepository.findByHomeId(homeId);
        Map<UUID, Long> deviceCountByRoom = allDevices.stream()
                .filter(d -> d.getRoom() != null)
                .collect(Collectors.groupingBy(d -> d.getRoom().getId(), Collectors.counting()));

        return rooms.stream()
                .map(room -> RoomResponse.fromEntity(room, deviceCountByRoom.getOrDefault(room.getId(), 0L).intValue()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public RoomResponse getRoomDetail(UUID userId, UUID homeId, UUID roomId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        Room room = roomRepository.findByIdAndHomeId(roomId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.ROOM_NOT_FOUND));

        List<Device> devices = deviceRepository.findByRoomId(roomId);
        return RoomResponse.fromEntity(room, devices.size());
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
        log.info("User {} deleted room '{}' (id: {}) in home {}", userId, room.getName(), roomId, homeId);
    }
}
