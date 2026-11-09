package com.hesta.backend.service.impl;

import com.hesta.backend.dto.access.MemberAccessDetailResponse;
import com.hesta.backend.dto.access.MemberDeviceAccessDto;
import com.hesta.backend.dto.access.MemberRoomAccessDto;
import com.hesta.backend.dto.access.UpdateMemberAccessRequest;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.HomeMemberAccessService;
import com.hesta.backend.service.MemberAccessContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class HomeMemberAccessServiceImpl implements HomeMemberAccessService {

    private final HomeAuthorizationService homeAuthorizationService;
    private final HomeMemberRepository homeMemberRepository;
    private final MemberRoomAccessRepository memberRoomAccessRepository;
    private final MemberDeviceAccessRepository memberDeviceAccessRepository;
    private final RoomRepository roomRepository;
    private final DeviceRepository deviceRepository;

    @Override
    @Transactional(readOnly = true)
    public MemberAccessDetailResponse getMemberAccess(UUID callerUserId, UUID homeId, UUID targetUserId) {
        HomeMember caller = homeMemberRepository.findByHomeIdAndUserId(homeId, callerUserId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        if (caller.getStatus() != MemberStatus.ACTIVE) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        boolean isCallerOwner = caller.getRole() == HomeRole.OWNER;
        boolean isSelf = callerUserId.equals(targetUserId);

        // Security check: Only OWNER or the member themself can view access
        if (!isCallerOwner && !isSelf) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        HomeMember targetMember = homeMemberRepository.findByHomeIdAndUserId(homeId, targetUserId)
                .orElseThrow(() -> new AppException(ErrorCode.MEMBER_NOT_FOUND));

        List<Room> allRooms = roomRepository.findByHomeId(homeId);
        List<Device> allDevices = deviceRepository.findByHomeIdWithRoomAndNode(homeId).stream()
                .filter(d -> !d.isDeleted())
                .toList();

        MemberAccessContext targetContext = homeAuthorizationService.loadAccessContext(homeId, targetUserId);

        List<MemberRoomAccessDto> roomDtos = new ArrayList<>();
        List<MemberDeviceAccessDto> deviceDtos = new ArrayList<>();

        if (targetMember.getRole() == HomeRole.OWNER || targetMember.getAccessScope() == AccessScope.ALL) {
            for (Room r : allRooms) {
                roomDtos.add(MemberRoomAccessDto.builder()
                        .roomId(r.getId())
                        .roomName(r.getName())
                        .accessLevel(AccessLevel.CONTROL)
                        .build());
            }
            for (Device d : allDevices) {
                deviceDtos.add(MemberDeviceAccessDto.builder()
                        .deviceId(d.getId())
                        .deviceName(d.getName())
                        .roomId(d.getRoom() != null ? d.getRoom().getId() : null)
                        .roomName(d.getRoom() != null ? d.getRoom().getName() : null)
                        .accessLevel(AccessLevel.CONTROL)
                        .build());
            }
        } else {
            // Scope CUSTOM
            Map<UUID, AccessLevel> roomLevels = targetContext.roomLevels();
            Map<UUID, AccessLevel> deviceOverrides = targetContext.deviceOverrides();

            for (Room r : allRooms) {
                AccessLevel level = roomLevels.getOrDefault(r.getId(), AccessLevel.NONE);
                // Self View (Correction C2): Only include accessible rooms
                if (isCallerOwner || level.canView()) {
                    roomDtos.add(MemberRoomAccessDto.builder()
                            .roomId(r.getId())
                            .roomName(r.getName())
                            .accessLevel(level)
                            .build());
                }
            }

            for (Device d : allDevices) {
                AccessLevel level = homeAuthorizationService.effectiveLevel(targetContext, d);
                // Self View (Correction C2): Only include accessible devices
                if (isCallerOwner || level.canView()) {
                    deviceDtos.add(MemberDeviceAccessDto.builder()
                            .deviceId(d.getId())
                            .deviceName(d.getName())
                            .roomId(d.getRoom() != null ? d.getRoom().getId() : null)
                            .roomName(d.getRoom() != null ? d.getRoom().getName() : null)
                            .accessLevel(level)
                            .build());
                }
            }
        }

        long version = targetMember.getJoinedAt() != null
                ? targetMember.getJoinedAt().toInstant().toEpochMilli()
                : Instant.now().toEpochMilli();

        return MemberAccessDetailResponse.builder()
                .userId(targetUserId)
                .memberId(targetMember.getId())
                .fullName(targetMember.getUser().getFullName())
                .role(targetMember.getRole().name())
                .accessScope(targetMember.getAccessScope() != null ? targetMember.getAccessScope() : AccessScope.ALL)
                .rooms(roomDtos)
                .devices(deviceDtos)
                .version(version)
                .build();
    }

    @Override
    @Transactional
    public MemberAccessDetailResponse updateMemberAccess(
            UUID callerUserId,
            UUID homeId,
            UUID targetUserId,
            UpdateMemberAccessRequest request) {

        homeAuthorizationService.requireOwner(callerUserId, homeId);

        if (request == null || request.getAccessScope() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        HomeMember targetMember = homeMemberRepository.findByHomeIdAndUserId(homeId, targetUserId)
                .orElseThrow(() -> new AppException(ErrorCode.MEMBER_NOT_FOUND));

        if (targetMember.getRole() == HomeRole.OWNER) {
            throw new AppException(ErrorCode.CANNOT_CHANGE_OWNER_ROLE);
        }
        if (targetMember.getStatus() != MemberStatus.ACTIVE) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        // Concurrency check (Correction C4)
        if (request.getExpectedVersion() != null) {
            long currentVersion = targetMember.getJoinedAt() != null
                    ? targetMember.getJoinedAt().toInstant().toEpochMilli()
                    : 0L;
            if (request.getExpectedVersion() != currentVersion) {
                throw new AppException(ErrorCode.MEMBER_ACCESS_CONFLICT);
            }
        }

        // Validate room and device IDs belong to this home (Correction C1)
        Map<UUID, Room> homeRoomsById = roomRepository.findByHomeId(homeId).stream()
                .collect(Collectors.toMap(Room::getId, r -> r));

        Map<UUID, Device> homeDevicesById = deviceRepository.findByHomeIdWithRoomAndNode(homeId).stream()
                .filter(d -> !d.isDeleted())
                .collect(Collectors.toMap(Device::getId, d -> d));

        List<String> invalidIds = new ArrayList<>();

        if (request.getRooms() != null) {
            for (MemberRoomAccessDto rDto : request.getRooms()) {
                if (rDto.getRoomId() != null && !homeRoomsById.containsKey(rDto.getRoomId())) {
                    invalidIds.add("room:" + rDto.getRoomId());
                }
            }
        }

        if (request.getDevices() != null) {
            for (MemberDeviceAccessDto dDto : request.getDevices()) {
                if (dDto.getDeviceId() != null && !homeDevicesById.containsKey(dDto.getDeviceId())) {
                    invalidIds.add("device:" + dDto.getDeviceId());
                }
            }
        }

        if (!invalidIds.isEmpty()) {
            log.warn("Invalid room/device IDs in access payload for home {}: {}", homeId, invalidIds);
            throw new AppException(ErrorCode.INVALID_ACCESS_PAYLOAD);
        }

        // Atomic replace
        targetMember.setAccessScope(request.getAccessScope());
        memberRoomAccessRepository.deleteByMemberId(targetMember.getId());
        memberDeviceAccessRepository.deleteByMemberId(targetMember.getId());
        memberRoomAccessRepository.flush();
        memberDeviceAccessRepository.flush();

        if (request.getAccessScope() == AccessScope.CUSTOM) {
            if (request.getRooms() != null) {
                for (MemberRoomAccessDto rDto : request.getRooms()) {
                    if (rDto.getRoomId() != null && rDto.getAccessLevel() != null) {
                        Room room = homeRoomsById.get(rDto.getRoomId());
                        MemberRoomAccess mra = MemberRoomAccess.builder()
                                .member(targetMember)
                                .room(room)
                                .accessLevel(rDto.getAccessLevel())
                                .build();
                        memberRoomAccessRepository.save(mra);
                    }
                }
            }

            if (request.getDevices() != null) {
                for (MemberDeviceAccessDto dDto : request.getDevices()) {
                    if (dDto.getDeviceId() != null && dDto.getAccessLevel() != null) {
                        Device device = homeDevicesById.get(dDto.getDeviceId());
                        MemberDeviceAccess mda = MemberDeviceAccess.builder()
                                .member(targetMember)
                                .device(device)
                                .accessLevel(dDto.getAccessLevel())
                                .build();
                        memberDeviceAccessRepository.save(mda);
                    }
                }
            }
        }

        homeMemberRepository.save(targetMember);
        log.info("Updated access scope to {} for member {} (user: {}) by owner {}",
                request.getAccessScope(), targetMember.getId(), targetUserId, callerUserId);

        return getMemberAccess(callerUserId, homeId, targetUserId);
    }
}

