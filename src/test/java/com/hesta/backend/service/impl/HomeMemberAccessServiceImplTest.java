package com.hesta.backend.service.impl;

import com.hesta.backend.dto.access.MemberAccessDetailResponse;
import com.hesta.backend.dto.access.MemberDeviceAccessDto;
import com.hesta.backend.dto.access.MemberRoomAccessDto;
import com.hesta.backend.dto.access.UpdateMemberAccessRequest;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.MemberDeviceAccessRepository;
import com.hesta.backend.repository.MemberRoomAccessRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HomeMemberAccessServiceImplTest {

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @Mock
    private HomeMemberRepository homeMemberRepository;

    @Mock
    private MemberRoomAccessRepository memberRoomAccessRepository;

    @Mock
    private MemberDeviceAccessRepository memberDeviceAccessRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @InjectMocks
    private HomeMemberAccessServiceImpl service;

    private UUID homeId;
    private UUID ownerUserId;
    private UUID memberUserId;
    private UUID otherUserId;

    private Home home;
    private User ownerUser;
    private User memberUser;
    private HomeMember ownerMember;
    private HomeMember customMember;

    private Room livingRoom;
    private Room bedroom;
    private Device light1;
    private Device light2;

    @BeforeEach
    void setUp() {
        homeId = UUID.randomUUID();
        ownerUserId = UUID.randomUUID();
        memberUserId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();

        home = Home.builder().id(homeId).name("Test Home").build();

        ownerUser = User.builder().id(ownerUserId).fullName("Owner User").email("owner@hesta.vn").build();
        memberUser = User.builder().id(memberUserId).fullName("Custom Member").email("member@hesta.vn").build();

        ownerMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(ownerUser)
                .role(HomeRole.OWNER)
                .status(MemberStatus.ACTIVE)
                .accessScope(AccessScope.ALL)
                .joinedAt(OffsetDateTime.now())
                .build();

        customMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(memberUser)
                .role(HomeRole.MEMBER)
                .status(MemberStatus.ACTIVE)
                .accessScope(AccessScope.CUSTOM)
                .joinedAt(OffsetDateTime.now())
                .build();

        livingRoom = Room.builder().id(UUID.randomUUID()).home(home).name("Living Room").build();
        bedroom = Room.builder().id(UUID.randomUUID()).home(home).name("Bedroom").build();

        light1 = Device.builder().id(UUID.randomUUID()).room(livingRoom).name("Living Light").isDeleted(false).build();
        light2 = Device.builder().id(UUID.randomUUID()).room(bedroom).name("Bed Light").isDeleted(false).build();
    }

    @Test
    @DisplayName("getMemberAccess: Owner viewing custom member sees full matrix including NONE items")
    void getMemberAccess_AsOwner_ReturnsFullMatrix() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(customMember));
        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom, bedroom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1, light2));

        Map<UUID, AccessLevel> roomLevels = Map.of(livingRoom.getId(), AccessLevel.CONTROL, bedroom.getId(), AccessLevel.NONE);
        MemberAccessContext context = new MemberAccessContext(homeId, memberUserId, customMember.getId(), HomeRole.MEMBER, MemberStatus.ACTIVE, AccessScope.CUSTOM, roomLevels, Map.of());
        when(homeAuthorizationService.loadAccessContext(homeId, memberUserId)).thenReturn(context);
        when(homeAuthorizationService.effectiveLevel(context, light1)).thenReturn(AccessLevel.CONTROL);
        when(homeAuthorizationService.effectiveLevel(context, light2)).thenReturn(AccessLevel.NONE);

        MemberAccessDetailResponse response = service.getMemberAccess(ownerUserId, homeId, memberUserId);

        assertNotNull(response);
        assertEquals(memberUserId, response.getUserId());
        assertEquals(2, response.getRooms().size());
        assertEquals(2, response.getDevices().size());
    }

    @Test
    @DisplayName("getMemberAccess: Self view (Correction C2) filters out NONE rooms and devices")
    void getMemberAccess_AsSelf_FiltersOutNoneItems() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(customMember));
        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom, bedroom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1, light2));

        Map<UUID, AccessLevel> roomLevels = Map.of(livingRoom.getId(), AccessLevel.CONTROL, bedroom.getId(), AccessLevel.NONE);
        MemberAccessContext context = new MemberAccessContext(homeId, memberUserId, customMember.getId(), HomeRole.MEMBER, MemberStatus.ACTIVE, AccessScope.CUSTOM, roomLevels, Map.of());
        when(homeAuthorizationService.loadAccessContext(homeId, memberUserId)).thenReturn(context);
        when(homeAuthorizationService.effectiveLevel(context, light1)).thenReturn(AccessLevel.CONTROL);
        when(homeAuthorizationService.effectiveLevel(context, light2)).thenReturn(AccessLevel.NONE);

        MemberAccessDetailResponse response = service.getMemberAccess(memberUserId, homeId, memberUserId);

        assertNotNull(response);
        assertEquals(1, response.getRooms().size());
        assertEquals(livingRoom.getId(), response.getRooms().get(0).getRoomId());
        assertEquals(1, response.getDevices().size());
        assertEquals(light1.getId(), response.getDevices().get(0).getDeviceId());
    }

    @Test
    @DisplayName("getMemberAccess: Non-owner third party viewing another member throws UNAUTHORIZED")
    void getMemberAccess_AsOtherNonOwner_ThrowsUnauthorized() {
        HomeMember otherMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(User.builder().id(otherUserId).build())
                .role(HomeRole.MEMBER)
                .status(MemberStatus.ACTIVE)
                .build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, otherUserId)).thenReturn(Optional.of(otherMember));

        AppException ex = assertThrows(AppException.class, () ->
                service.getMemberAccess(otherUserId, homeId, memberUserId));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("updateMemberAccess: Valid CUSTOM payload atomically replaces room and device access")
    void updateMemberAccess_ValidCustom_ReplacesMatrix() {
        when(homeAuthorizationService.requireOwner(ownerUserId, homeId)).thenReturn(home);
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(customMember));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));
        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom, bedroom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1, light2));

        MemberAccessContext updatedCtx = new MemberAccessContext(homeId, memberUserId, customMember.getId(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                AccessScope.CUSTOM, Map.of(livingRoom.getId(), AccessLevel.CONTROL), Map.of(light1.getId(), AccessLevel.CONTROL));
        when(homeAuthorizationService.loadAccessContext(homeId, memberUserId)).thenReturn(updatedCtx);
        when(homeAuthorizationService.effectiveLevel(eq(updatedCtx), any(Device.class))).thenReturn(AccessLevel.CONTROL);

        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder()
                .accessScope(AccessScope.CUSTOM)
                .rooms(List.of(MemberRoomAccessDto.builder().roomId(livingRoom.getId()).accessLevel(AccessLevel.CONTROL).build()))
                .devices(List.of(MemberDeviceAccessDto.builder().deviceId(light1.getId()).accessLevel(AccessLevel.CONTROL).build()))
                .build();

        MemberAccessDetailResponse response = service.updateMemberAccess(ownerUserId, homeId, memberUserId, request);

        assertNotNull(response);
        verify(memberRoomAccessRepository).deleteByMemberId(customMember.getId());
        verify(memberDeviceAccessRepository).deleteByMemberId(customMember.getId());
        verify(memberRoomAccessRepository).save(any());
        verify(memberDeviceAccessRepository).save(any());
        verify(homeMemberRepository).save(customMember);
    }

    @Test
    @DisplayName("updateMemberAccess: Invalid room/device IDs not in home throws INVALID_ACCESS_PAYLOAD (Correction C1)")
    void updateMemberAccess_InvalidIds_ThrowsInvalidAccessPayload() {
        when(homeAuthorizationService.requireOwner(ownerUserId, homeId)).thenReturn(home);
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(customMember));
        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1));

        UUID randomForeignRoomId = UUID.randomUUID();
        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder()
                .accessScope(AccessScope.CUSTOM)
                .rooms(List.of(MemberRoomAccessDto.builder().roomId(randomForeignRoomId).accessLevel(AccessLevel.VIEW).build()))
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                service.updateMemberAccess(ownerUserId, homeId, memberUserId, request));
        assertEquals(ErrorCode.INVALID_ACCESS_PAYLOAD, ex.getErrorCode());
    }

    @Test
    @DisplayName("updateMemberAccess: Target is OWNER throws CANNOT_CHANGE_OWNER_ROLE")
    void updateMemberAccess_TargetIsOwner_ThrowsCannotChangeOwnerRole() {
        when(homeAuthorizationService.requireOwner(ownerUserId, homeId)).thenReturn(home);
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));

        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder()
                .accessScope(AccessScope.CUSTOM)
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                service.updateMemberAccess(ownerUserId, homeId, ownerUserId, request));
        assertEquals(ErrorCode.CANNOT_CHANGE_OWNER_ROLE, ex.getErrorCode());
    }

    @Test
    @DisplayName("updateMemberAccess: Concurrency conflict (expectedVersion mismatch) throws MEMBER_ACCESS_CONFLICT (Correction C4)")
    void updateMemberAccess_VersionConflict_ThrowsConflict() {
        when(homeAuthorizationService.requireOwner(ownerUserId, homeId)).thenReturn(home);
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(customMember));

        long currentVersion = customMember.getJoinedAt().toInstant().toEpochMilli();
        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder()
                .accessScope(AccessScope.ALL)
                .expectedVersion(currentVersion + 1000L) // Stale version
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                service.updateMemberAccess(ownerUserId, homeId, memberUserId, request));
        assertEquals(ErrorCode.MEMBER_ACCESS_CONFLICT, ex.getErrorCode());
    }
}
