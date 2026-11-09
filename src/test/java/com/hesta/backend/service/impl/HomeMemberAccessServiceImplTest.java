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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Mock
    private ApplicationEventPublisher eventPublisher;

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

        home = Home.builder().id(homeId).name("Nhà Thông Minh").build();

        ownerUser = User.builder().id(ownerUserId).fullName("Chủ Nhà").email("owner@hesta.vn").build();
        memberUser = User.builder().id(memberUserId).fullName("Thành Viên").email("member@hesta.vn").build();

        ownerMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(ownerUser)
                .role(HomeRole.OWNER)
                .status(MemberStatus.ACTIVE)
                .accessScope(AccessScope.ALL)
                .build();

        customMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(memberUser)
                .role(HomeRole.MEMBER)
                .status(MemberStatus.ACTIVE)
                .accessScope(AccessScope.CUSTOM)
                .build();

        livingRoom = Room.builder().id(UUID.randomUUID()).name("Phòng Khách").home(home).build();
        bedroom = Room.builder().id(UUID.randomUUID()).name("Phòng Ngủ").home(home).build();

        light1 = Device.builder().id(UUID.randomUUID()).name("Đèn Phòng Khách").room(livingRoom).isDeleted(false).build();
        light2 = Device.builder().id(UUID.randomUUID()).name("Đèn Phòng Ngủ").room(bedroom).isDeleted(false).build();

        ReflectionTestUtils.setField(service, "customScopeEnabled", true);
    }

    @Test
    @DisplayName("GET - OWNER view full list including NONE rooms and override flags")
    void testGetMemberAccess_OwnerView() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(customMember));
        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom, bedroom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1, light2));

        MemberRoomAccess mra = MemberRoomAccess.builder().room(livingRoom).accessLevel(AccessLevel.CONTROL).build();
        when(memberRoomAccessRepository.findByMemberId(customMember.getId())).thenReturn(List.of(mra));

        MemberDeviceAccess mda = MemberDeviceAccess.builder().device(light1).accessLevel(AccessLevel.VIEW).build();
        when(memberDeviceAccessRepository.findByMemberId(customMember.getId())).thenReturn(List.of(mda));

        MemberAccessDetailResponse response = service.getMemberAccess(ownerUserId, homeId, memberUserId);

        assertNotNull(response);
        assertEquals(memberUserId, response.getMemberUserId());
        assertEquals("Thành Viên", response.getFullName());
        assertEquals(AccessScope.CUSTOM, response.getScope());
        assertFalse(response.isReadOnly());
        assertNotNull(response.getVersion());

        // Rooms: livingRoom has CONTROL, bedroom has NONE
        assertEquals(2, response.getRooms().size());
        Optional<MemberRoomAccessDto> lrOpt = response.getRooms().stream()
                .filter(r -> r.getRoomId().equals(livingRoom.getId())).findFirst();
        assertTrue(lrOpt.isPresent());
        assertEquals(AccessLevel.CONTROL, lrOpt.get().getLevel());

        Optional<MemberRoomAccessDto> brOpt = response.getRooms().stream()
                .filter(r -> r.getRoomId().equals(bedroom.getId())).findFirst();
        assertTrue(brOpt.isPresent());
        assertEquals(AccessLevel.NONE, brOpt.get().getLevel());

        // Devices: light1 has override VIEW, light2 inherits bedroom (NONE)
        assertEquals(2, response.getDevices().size());
        Optional<MemberDeviceAccessDto> l1Opt = response.getDevices().stream()
                .filter(d -> d.getDeviceId().equals(light1.getId())).findFirst();
        assertTrue(l1Opt.isPresent());
        assertTrue(l1Opt.get().getIsOverride());
        assertEquals(AccessLevel.VIEW, l1Opt.get().getEffectiveLevel());
        assertEquals(AccessLevel.CONTROL, l1Opt.get().getRoomLevel());

        Optional<MemberDeviceAccessDto> l2Opt = response.getDevices().stream()
                .filter(d -> d.getDeviceId().equals(light2.getId())).findFirst();
        assertTrue(l2Opt.isPresent());
        assertFalse(l2Opt.get().getIsOverride());
        assertEquals(AccessLevel.NONE, l2Opt.get().getEffectiveLevel());
        assertEquals(AccessLevel.NONE, l2Opt.get().getRoomLevel());
    }

    @Test
    @DisplayName("GET - Self view: ONLY rooms and devices with level >= VIEW (hidden rooms/devices excluded)")
    void testGetMemberAccess_SelfView_Filtered() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(customMember));
        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom, bedroom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1, light2));

        // Living room is CONTROL, Bedroom is NONE
        MemberRoomAccess mra = MemberRoomAccess.builder().room(livingRoom).accessLevel(AccessLevel.CONTROL).build();
        when(memberRoomAccessRepository.findByMemberId(customMember.getId())).thenReturn(List.of(mra));

        // Light1 has override VIEW, Light2 inherits bedroom (NONE)
        MemberDeviceAccess mda = MemberDeviceAccess.builder().device(light1).accessLevel(AccessLevel.VIEW).build();
        when(memberDeviceAccessRepository.findByMemberId(customMember.getId())).thenReturn(List.of(mda));

        MemberAccessDetailResponse response = service.getMemberAccess(memberUserId, homeId, memberUserId);

        assertNotNull(response);
        assertTrue(response.isReadOnly());

        // Living room included, Bedroom (NONE) excluded
        assertEquals(1, response.getRooms().size());
        assertEquals(livingRoom.getId(), response.getRooms().get(0).getRoomId());

        // Light1 (VIEW) included, Light2 (NONE) excluded
        assertEquals(1, response.getDevices().size());
        assertEquals(light1.getId(), response.getDevices().get(0).getDeviceId());
    }

    @Test
    @DisplayName("GET - Target is OWNER returns scope ALL and readOnly=true")
    void testGetMemberAccess_TargetIsOwner() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));
        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom, bedroom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1, light2));

        MemberAccessDetailResponse response = service.getMemberAccess(ownerUserId, homeId, ownerUserId);

        assertNotNull(response);
        assertEquals(AccessScope.ALL, response.getScope());
        assertTrue(response.isReadOnly());
        assertEquals(2, response.getRooms().size());
        assertEquals(AccessLevel.CONTROL, response.getRooms().get(0).getLevel());
    }

    @Test
    @DisplayName("GET - Non-owner reading someone else returns 403 UNAUTHORIZED")
    void testGetMemberAccess_NonOwnerReadingOther_Forbidden() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, otherUserId)).thenReturn(Optional.of(
                HomeMember.builder().user(User.builder().id(otherUserId).build())
                        .role(HomeRole.MEMBER).status(MemberStatus.ACTIVE).build()));

        AppException ex = assertThrows(AppException.class, () ->
                service.getMemberAccess(otherUserId, homeId, memberUserId));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("GET - Target not active or not in home returns 404 MEMBER_NOT_FOUND")
    void testGetMemberAccess_TargetNotActive_NotFound() {
        HomeMember suspendedMember = HomeMember.builder().user(memberUser).role(HomeRole.MEMBER).status(MemberStatus.DISABLED).build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(suspendedMember));

        AppException ex = assertThrows(AppException.class, () ->
                service.getMemberAccess(ownerUserId, homeId, memberUserId));
        assertEquals(ErrorCode.MEMBER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("PUT - Valid CUSTOM with flag on updates permissions, publishes event, and logs audit")
    void testUpdateMemberAccess_Success() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));
        when(homeMemberRepository.findByHomeIdAndUserIdForUpdate(homeId, memberUserId)).thenReturn(Optional.of(customMember));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(customMember));

        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom, bedroom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1, light2));

        String currentVersion = HomeMemberAccessServiceImpl.computeAccessVersion(AccessScope.CUSTOM, List.of(), List.of());

        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder()
                .scope(AccessScope.CUSTOM)
                .version(currentVersion)
                .rooms(List.of(MemberRoomAccessDto.builder().roomId(livingRoom.getId()).level(AccessLevel.CONTROL).build()))
                .devices(List.of(MemberDeviceAccessDto.builder().deviceId(light1.getId()).level(AccessLevel.VIEW).build()))
                .build();

        MemberAccessDetailResponse response = service.updateMemberAccess(ownerUserId, homeId, memberUserId, request);

        assertNotNull(response);
        verify(memberRoomAccessRepository).deleteByMemberId(customMember.getId());
        verify(memberDeviceAccessRepository).deleteByMemberId(customMember.getId());
        verify(memberRoomAccessRepository).save(any(MemberRoomAccess.class));
        verify(memberDeviceAccessRepository).save(any(MemberDeviceAccess.class));
        verify(homeMemberRepository).save(customMember);
        verify(eventPublisher).publishEvent(any(MemberAccessChangedEvent.class));
    }

    @Test
    @DisplayName("PUT - Flag off with scope CUSTOM throws 409 FEATURE_DISABLED")
    void testUpdateMemberAccess_FlagOff_ThrowsFeatureDisabled() {
        ReflectionTestUtils.setField(service, "customScopeEnabled", false);
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));

        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder()
                .scope(AccessScope.CUSTOM)
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                service.updateMemberAccess(ownerUserId, homeId, memberUserId, request));
        assertEquals(ErrorCode.FEATURE_DISABLED, ex.getErrorCode());
    }

    @Test
    @DisplayName("PUT - Scope ALL clears all rows and succeeds even if flag is off")
    void testUpdateMemberAccess_ScopeAll_ClearsRows() {
        ReflectionTestUtils.setField(service, "customScopeEnabled", false);
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));
        when(homeMemberRepository.findByHomeIdAndUserIdForUpdate(homeId, memberUserId)).thenReturn(Optional.of(customMember));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(customMember));
        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1));

        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder()
                .scope(AccessScope.ALL)
                .build();

        MemberAccessDetailResponse response = service.updateMemberAccess(ownerUserId, homeId, memberUserId, request);

        assertNotNull(response);
        assertEquals(AccessScope.ALL, response.getScope());
        verify(memberRoomAccessRepository).deleteByMemberId(customMember.getId());
        verify(memberDeviceAccessRepository).deleteByMemberId(customMember.getId());
        verify(memberRoomAccessRepository, never()).save(any(MemberRoomAccess.class));
    }

    @Test
    @DisplayName("PUT - Target is OWNER throws 409 CANNOT_MODIFY_OWNER_ACCESS")
    void testUpdateMemberAccess_TargetOwner_ThrowsCannotModify() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));
        when(homeMemberRepository.findByHomeIdAndUserIdForUpdate(homeId, otherUserId)).thenReturn(Optional.of(
                HomeMember.builder().user(User.builder().id(otherUserId).build()).role(HomeRole.OWNER).status(MemberStatus.ACTIVE).build()));

        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder().scope(AccessScope.CUSTOM).build();

        AppException ex = assertThrows(AppException.class, () ->
                service.updateMemberAccess(ownerUserId, homeId, otherUserId, request));
        assertEquals(ErrorCode.CANNOT_MODIFY_OWNER_ACCESS, ex.getErrorCode());
    }

    @Test
    @DisplayName("PUT - Target is caller themself throws 409 CANNOT_MODIFY_OWNER_ACCESS")
    void testUpdateMemberAccess_TargetSelf_ThrowsCannotModify() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));

        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder().scope(AccessScope.CUSTOM).build();

        AppException ex = assertThrows(AppException.class, () ->
                service.updateMemberAccess(ownerUserId, homeId, ownerUserId, request));
        assertEquals(ErrorCode.CANNOT_MODIFY_OWNER_ACCESS, ex.getErrorCode());
    }

    @Test
    @DisplayName("PUT - Stale version throws 409 STALE_ACCESS_VERSION")
    void testUpdateMemberAccess_StaleVersion_ThrowsConflict() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));
        when(homeMemberRepository.findByHomeIdAndUserIdForUpdate(homeId, memberUserId)).thenReturn(Optional.of(customMember));

        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder()
                .scope(AccessScope.CUSTOM)
                .version("old-or-wrong-sha256-version")
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                service.updateMemberAccess(ownerUserId, homeId, memberUserId, request));
        assertEquals(ErrorCode.STALE_ACCESS_VERSION, ex.getErrorCode());
    }

    @Test
    @DisplayName("PUT - Duplicate room IDs or foreign room IDs throw 400 INVALID_ACCESS_PAYLOAD")
    void testUpdateMemberAccess_InvalidPayload_ThrowsBadRequest() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerUserId)).thenReturn(Optional.of(ownerMember));
        when(homeMemberRepository.findByHomeIdAndUserIdForUpdate(homeId, memberUserId)).thenReturn(Optional.of(customMember));
        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1));

        UUID foreignRoomId = UUID.randomUUID();

        // Foreign room ID
        UpdateMemberAccessRequest request1 = UpdateMemberAccessRequest.builder()
                .scope(AccessScope.CUSTOM)
                .rooms(List.of(MemberRoomAccessDto.builder().roomId(foreignRoomId).level(AccessLevel.CONTROL).build()))
                .build();

        AppException ex1 = assertThrows(AppException.class, () ->
                service.updateMemberAccess(ownerUserId, homeId, memberUserId, request1));
        assertEquals(ErrorCode.INVALID_ACCESS_PAYLOAD, ex1.getErrorCode());

        // Duplicate room ID
        UpdateMemberAccessRequest request2 = UpdateMemberAccessRequest.builder()
                .scope(AccessScope.CUSTOM)
                .rooms(List.of(
                        MemberRoomAccessDto.builder().roomId(livingRoom.getId()).level(AccessLevel.CONTROL).build(),
                        MemberRoomAccessDto.builder().roomId(livingRoom.getId()).level(AccessLevel.VIEW).build()
                ))
                .build();

        AppException ex2 = assertThrows(AppException.class, () ->
                service.updateMemberAccess(ownerUserId, homeId, memberUserId, request2));
        assertEquals(ErrorCode.INVALID_ACCESS_PAYLOAD, ex2.getErrorCode());
    }
}
