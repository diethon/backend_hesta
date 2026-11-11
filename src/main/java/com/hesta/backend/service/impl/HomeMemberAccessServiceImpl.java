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
import com.hesta.backend.event.MemberAccessChangedEvent;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.HomeMemberAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private final ApplicationEventPublisher eventPublisher;

    @Value("${access.custom-scope.enabled:false}")
    private boolean customScopeEnabled;

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

        if (targetMember.getStatus() != MemberStatus.ACTIVE) {
            throw new AppException(ErrorCode.MEMBER_NOT_FOUND);
        }

        List<Room> allRooms = roomRepository.findByHomeId(homeId);
        List<Device> allDevices = deviceRepository.findByHomeIdWithRoomAndNode(homeId).stream()
                .filter(d -> !d.isDeleted())
                .toList();

        List<MemberRoomAccess> storedRooms = memberRoomAccessRepository.findByMemberId(targetMember.getId());
        List<MemberDeviceAccess> storedDevices = memberDeviceAccessRepository.findByMemberId(targetMember.getId());

        String version = computeAccessVersion(targetMember.getAccessScope(), storedRooms, storedDevices);

        boolean isTargetOwner = targetMember.getRole() == HomeRole.OWNER;
        AccessScope scope = isTargetOwner ? AccessScope.ALL : (targetMember.getAccessScope() != null ? targetMember.getAccessScope() : AccessScope.ALL);
        boolean readOnly = isTargetOwner || !isCallerOwner;

        List<MemberRoomAccessDto> roomDtos = new ArrayList<>();
        List<MemberDeviceAccessDto> deviceDtos = new ArrayList<>();

        if (isTargetOwner || scope == AccessScope.ALL) {
            for (Room r : allRooms) {
                roomDtos.add(MemberRoomAccessDto.builder()
                        .roomId(r.getId())
                        .roomName(r.getName())
                        .level(AccessLevel.CONTROL)
                        .build());
            }
            for (Device d : allDevices) {
                deviceDtos.add(MemberDeviceAccessDto.builder()
                        .deviceId(d.getId())
                        .deviceName(d.getName())
                        .roomId(d.getRoom() != null ? d.getRoom().getId() : null)
                        .roomName(d.getRoom() != null ? d.getRoom().getName() : null)
                        .roomLevel(AccessLevel.CONTROL)
                        .effectiveLevel(AccessLevel.CONTROL)
                        .isOverride(false)
                        .level(AccessLevel.CONTROL)
                        .build());
            }
        } else {
            // Scope CUSTOM
            Map<UUID, AccessLevel> roomLevels = storedRooms.stream()
                    .filter(r -> r.getRoom() != null && r.getAccessLevel() != null)
                    .collect(Collectors.toMap(r -> r.getRoom().getId(), MemberRoomAccess::getAccessLevel, (a, b) -> a));

            Map<UUID, AccessLevel> deviceOverrides = storedDevices.stream()
                    .filter(d -> d.getDevice() != null && d.getAccessLevel() != null)
                    .collect(Collectors.toMap(d -> d.getDevice().getId(), MemberDeviceAccess::getAccessLevel, (a, b) -> a));

            for (Room r : allRooms) {
                AccessLevel level = roomLevels.getOrDefault(r.getId(), AccessLevel.NONE);
                // Self View: ONLY rooms with effective level >= VIEW
                if (isCallerOwner || level.canView()) {
                    roomDtos.add(MemberRoomAccessDto.builder()
                            .roomId(r.getId())
                            .roomName(r.getName())
                            .level(level)
                            .build());
                }
            }

            for (Device d : allDevices) {
                UUID rId = d.getRoom() != null ? d.getRoom().getId() : null;
                AccessLevel rLevel = rId != null ? roomLevels.getOrDefault(rId, AccessLevel.NONE) : AccessLevel.NONE;
                boolean hasOverride = deviceOverrides.containsKey(d.getId());
                AccessLevel effLevel = hasOverride ? deviceOverrides.get(d.getId()) : rLevel;

                // Self View: ONLY devices with effective level >= VIEW
                if (isCallerOwner || effLevel.canView()) {
                    deviceDtos.add(MemberDeviceAccessDto.builder()
                            .deviceId(d.getId())
                            .deviceName(d.getName())
                            .roomId(rId)
                            .roomName(d.getRoom() != null ? d.getRoom().getName() : null)
                            .roomLevel(rLevel)
                            .effectiveLevel(effLevel)
                            .isOverride(hasOverride)
                            .level(effLevel)
                            .build());
                }
            }
        }

        return MemberAccessDetailResponse.builder()
                .memberUserId(targetUserId)
                .memberId(targetMember.getId())
                .fullName(targetMember.getUser().getFullName())
                .role(targetMember.getRole().name())
                .scope(scope)
                .version(version)
                .readOnly(readOnly)
                .rooms(roomDtos)
                .devices(deviceDtos)
                .build();
    }

    @Override
    @Transactional
    public MemberAccessDetailResponse updateMemberAccess(
            UUID callerUserId,
            UUID homeId,
            UUID targetUserId,
            UpdateMemberAccessRequest request) {

        HomeMember caller = homeMemberRepository.findByHomeIdAndUserId(homeId, callerUserId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));

        if (caller.getStatus() != MemberStatus.ACTIVE || caller.getRole() != HomeRole.OWNER) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        if (callerUserId.equals(targetUserId)) {
            throw new AppException(ErrorCode.CANNOT_MODIFY_OWNER_ACCESS);
        }

        if (request == null || request.getScope() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        AccessScope newScope = request.getScope();

        // Feature flag check
        if (newScope == AccessScope.CUSTOM && !customScopeEnabled) {
            throw new AppException(ErrorCode.FEATURE_DISABLED, "Chưa bật quyền tùy chỉnh");
        }

        // Concurrency lock: SELECT ... FOR UPDATE on target member row
        HomeMember targetMember = homeMemberRepository.findByHomeIdAndUserIdForUpdate(homeId, targetUserId)
                .orElseThrow(() -> new AppException(ErrorCode.MEMBER_NOT_FOUND));

        if (targetMember.getStatus() != MemberStatus.ACTIVE) {
            throw new AppException(ErrorCode.MEMBER_NOT_FOUND);
        }

        if (targetMember.getRole() == HomeRole.OWNER) {
            throw new AppException(ErrorCode.CANNOT_MODIFY_OWNER_ACCESS);
        }

        List<MemberRoomAccess> currentStoredRooms = memberRoomAccessRepository.findByMemberId(targetMember.getId());
        List<MemberDeviceAccess> currentStoredDevices = memberDeviceAccessRepository.findByMemberId(targetMember.getId());

        String currentVersion = computeAccessVersion(targetMember.getAccessScope(), currentStoredRooms, currentStoredDevices);

        if (request.getVersion() != null && !request.getVersion().equalsIgnoreCase(currentVersion)) {
            throw new AppException(ErrorCode.STALE_ACCESS_VERSION,
                    "Phiên bản cấu hình phân quyền đã cũ (hiện tại: " + currentVersion + "). Vui lòng tải lại dữ liệu.");
        }

        Map<UUID, Room> homeRoomsById = roomRepository.findByHomeId(homeId).stream()
                .collect(Collectors.toMap(Room::getId, r -> r));

        Map<UUID, Device> homeDevicesById = deviceRepository.findByHomeIdWithRoomAndNode(homeId).stream()
                .filter(d -> !d.isDeleted())
                .collect(Collectors.toMap(Device::getId, d -> d));

        List<String> offendingIds = new ArrayList<>();

        if (newScope == AccessScope.CUSTOM) {
            Set<UUID> seenRooms = new HashSet<>();
            if (request.getRooms() != null) {
                for (MemberRoomAccessDto rDto : request.getRooms()) {
                    if (rDto.getRoomId() == null) {
                        offendingIds.add("room:null");
                        continue;
                    }
                    if (rDto.getLevel() == null) {
                        offendingIds.add("room_level_null:" + rDto.getRoomId());
                    }
                    if (!seenRooms.add(rDto.getRoomId())) {
                        offendingIds.add("duplicate_room:" + rDto.getRoomId());
                    }
                    if (!homeRoomsById.containsKey(rDto.getRoomId())) {
                        offendingIds.add("room:" + rDto.getRoomId());
                    }
                }
            }

            Set<UUID> seenDevices = new HashSet<>();
            if (request.getDevices() != null) {
                for (MemberDeviceAccessDto dDto : request.getDevices()) {
                    if (dDto.getDeviceId() == null) {
                        offendingIds.add("device:null");
                        continue;
                    }
                    if (dDto.getLevel() == null) {
                        offendingIds.add("device_level_null:" + dDto.getDeviceId());
                    }
                    if (!seenDevices.add(dDto.getDeviceId())) {
                        offendingIds.add("duplicate_device:" + dDto.getDeviceId());
                    }
                    if (!homeDevicesById.containsKey(dDto.getDeviceId())) {
                        offendingIds.add("device:" + dDto.getDeviceId());
                    }
                }
            }

            if (!offendingIds.isEmpty()) {
                log.warn("Invalid room/device IDs in access payload for home {}: {}", homeId, offendingIds);
                throw new AppException(ErrorCode.INVALID_ACCESS_PAYLOAD, "Dữ liệu phân quyền không hợp lệ: " + offendingIds);
            }
        }

        // Atomic replace
        targetMember.setAccessScope(newScope);
        memberRoomAccessRepository.deleteByMemberId(targetMember.getId());
        memberDeviceAccessRepository.deleteByMemberId(targetMember.getId());
        memberRoomAccessRepository.flush();
        memberDeviceAccessRepository.flush();

        int roomCount = 0;
        int deviceOverrideCount = 0;

        if (newScope == AccessScope.CUSTOM) {
            if (request.getRooms() != null) {
                for (MemberRoomAccessDto rDto : request.getRooms()) {
                    if (rDto.getRoomId() != null && rDto.getLevel() != null) {
                        Room room = homeRoomsById.get(rDto.getRoomId());
                        if (room != null) {
                            MemberRoomAccess mra = MemberRoomAccess.builder()
                                    .member(targetMember)
                                    .room(room)
                                    .accessLevel(rDto.getLevel())
                                    .build();
                            memberRoomAccessRepository.save(mra);
                            roomCount++;
                        }
                    }
                }
            }

            if (request.getDevices() != null) {
                for (MemberDeviceAccessDto dDto : request.getDevices()) {
                    if (dDto.getDeviceId() != null && dDto.getLevel() != null) {
                        Device device = homeDevicesById.get(dDto.getDeviceId());
                        if (device != null) {
                            MemberDeviceAccess mda = MemberDeviceAccess.builder()
                                    .member(targetMember)
                                    .device(device)
                                    .accessLevel(dDto.getLevel())
                                    .build();
                            memberDeviceAccessRepository.save(mda);
                            deviceOverrideCount++;
                        }
                    }
                }
            }
        }

        homeMemberRepository.save(targetMember);
        homeMemberRepository.flush();

        // Audit log (no names, no emails)
        log.info("AUDIT: Member access updated: callerUserId={}, targetUserId={}, scope={}, roomRows={}, deviceOverrides={}",
                callerUserId, targetUserId, newScope, roomCount, deviceOverrideCount);

        // Publish event for downstream listeners (Phase 2c)
        eventPublisher.publishEvent(new MemberAccessChangedEvent(homeId, targetUserId));

        return getMemberAccess(callerUserId, homeId, targetUserId);
    }

    public static String computeAccessVersion(
            AccessScope scope,
            List<MemberRoomAccess> roomAccesses,
            List<MemberDeviceAccess> deviceAccesses) {

        AccessScope effectiveScope = scope != null ? scope : AccessScope.ALL;
        StringBuilder canonical = new StringBuilder(effectiveScope.name());
        canonical.append(";rooms=");

        if (effectiveScope == AccessScope.CUSTOM && roomAccesses != null && !roomAccesses.isEmpty()) {
            List<String> sortedRooms = roomAccesses.stream()
                    .filter(r -> r.getRoom() != null && r.getAccessLevel() != null)
                    .map(r -> r.getRoom().getId().toString() + ":" + r.getAccessLevel().name())
                    .sorted()
                    .toList();
            canonical.append(String.join(",", sortedRooms));
        }

        canonical.append(";devices=");
        if (effectiveScope == AccessScope.CUSTOM && deviceAccesses != null && !deviceAccesses.isEmpty()) {
            List<String> sortedDevices = deviceAccesses.stream()
                    .filter(d -> d.getDevice() != null && d.getAccessLevel() != null)
                    .map(d -> d.getDevice().getId().toString() + ":" + d.getAccessLevel().name())
                    .sorted()
                    .toList();
            canonical.append(String.join(",", sortedDevices));
        }

        return sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
