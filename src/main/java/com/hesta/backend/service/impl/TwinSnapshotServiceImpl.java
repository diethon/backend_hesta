package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.TwinHomeSnapshotResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.SensorReading;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.repository.TwinSnapshotRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import com.hesta.backend.service.TwinSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TwinSnapshotServiceImpl implements TwinSnapshotService {
    private final HomeAuthorizationService homeAuthorizationService;
    private final RoomRepository roomRepository;
    private final TwinSnapshotRepository twinSnapshotRepository;
    private final TwinSnapshotMapper mapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TwinHomeSnapshotResponse getSnapshot(UUID userId, UUID homeId) {
        Home home = homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext ctx = homeAuthorizationService.loadAccessContext(homeId, userId);

        List<Device> allDevices = twinSnapshotRepository.findDevicesByHomeId(homeId);
        List<SensorReading> allReadings = twinSnapshotRepository.findLatestReadingsByHomeId(homeId);
        List<Room> allRooms = roomRepository.findByHomeId(homeId);

        if (ctx.isOwner() || ctx.scope() == AccessScope.ALL) {
            return mapper.home(home, allRooms, allDevices, allReadings);
        }

        // Scope CUSTOM: filter out NONE devices
        List<Device> visibleDevices = allDevices.stream()
                .filter(d -> !d.isDeleted())
                .filter(d -> homeAuthorizationService.effectiveLevel(ctx, d).canView())
                .toList();

        Set<UUID> visibleDeviceIds = visibleDevices.stream().map(Device::getId).collect(Collectors.toSet());
        List<SensorReading> visibleReadings = allReadings.stream()
                .filter(r -> r.getDevice() != null && visibleDeviceIds.contains(r.getDevice().getId()))
                .toList();

        Set<UUID> roomsWithAccessibleDevice = visibleDevices.stream()
                .map(d -> d.getRoom() != null ? d.getRoom().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Rooms with no accessible device keep their 3D shell but their name label is removed
        List<Room> filteredRooms = allRooms.stream().map(r -> {
            if (!roomsWithAccessibleDevice.contains(r.getId())) {
                return Room.builder()
                        .id(r.getId())
                        .home(r.getHome())
                        .name("")
                        .icon(r.getIcon())
                        .layoutX(r.getLayoutX())
                        .layoutY(r.getLayoutY())
                        .createdAt(r.getCreatedAt())
                        .build();
            }
            return r;
        }).toList();

        return mapper.home(home, filteredRooms, visibleDevices, visibleReadings);
    }
}
