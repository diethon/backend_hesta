package com.hesta.backend.service.impl;

import com.hesta.backend.entity.*;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HomeMemberAccessLifecycleTest {

    @Mock
    private HomeRepository homeRepository;

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
    private UserRepository userRepository;

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @Mock
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private HomeServiceImpl homeService;

    private UUID homeId;
    private UUID ownerId;
    private UUID memberUserId;

    private Home home;
    private User ownerUser;
    private User memberUser;
    private HomeMember ownerMember;
    private HomeMember targetMember;

    private Room livingRoom;
    private Device device1;

    @BeforeEach
    void setUp() {
        homeId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        memberUserId = UUID.randomUUID();

        home = Home.builder().id(homeId).name("Nhà Test").build();
        ownerUser = User.builder().id(ownerId).fullName("Chủ nhà").build();
        memberUser = User.builder().id(memberUserId).fullName("Thành viên").build();

        ownerMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(ownerUser)
                .role(HomeRole.OWNER)
                .status(MemberStatus.ACTIVE)
                .build();

        targetMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(memberUser)
                .role(HomeRole.MEMBER)
                .status(MemberStatus.ACTIVE)
                .accessScope(AccessScope.CUSTOM)
                .build();

        livingRoom = Room.builder().id(UUID.randomUUID()).home(home).name("Khách").build();
        device1 = Device.builder().id(UUID.randomUUID()).room(livingRoom).name("Đèn").isDeleted(false).build();
    }

    @Test
    @DisplayName("Lifecycle - removeMember deletes access rows for room and device")
    void testRemoveMember_DeletesAccessRows() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerId)).thenReturn(Optional.of(ownerMember));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(targetMember));

        homeService.removeMember(ownerId, homeId, memberUserId);

        verify(memberRoomAccessRepository).deleteByMemberId(targetMember.getId());
        verify(memberDeviceAccessRepository).deleteByMemberId(targetMember.getId());
        verify(homeMemberRepository).delete(targetMember);
    }

    @Test
    @DisplayName("Lifecycle - updateMemberRole to OWNER forces scope ALL and deletes access rows")
    void testUpdateMemberRole_ToOwner_ForcesAllAndClearsRows() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerId)).thenReturn(Optional.of(ownerMember));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberUserId)).thenReturn(Optional.of(targetMember));

        homeService.updateMemberRole(ownerId, homeId, memberUserId, HomeRole.OWNER);

        assertEquals(HomeRole.OWNER, targetMember.getRole());
        assertEquals(AccessScope.ALL, targetMember.getAccessScope());
        verify(memberRoomAccessRepository).deleteByMemberId(targetMember.getId());
        verify(memberDeviceAccessRepository).deleteByMemberId(targetMember.getId());
        verify(homeMemberRepository).save(targetMember);
    }

    @Test
    @DisplayName("Lifecycle - Deleted device (is_deleted=true) yields NONE level and never breaks effective calculation")
    void testDeletedDevice_YieldsNone() {
        Device deletedDevice = Device.builder()
                .id(UUID.randomUUID())
                .room(livingRoom)
                .isDeleted(true)
                .build();

        MemberAccessContext context = new MemberAccessContext(
                homeId,
                memberUserId,
                targetMember.getId(),
                HomeRole.MEMBER,
                MemberStatus.ACTIVE,
                AccessScope.CUSTOM,
                Map.of(livingRoom.getId(), AccessLevel.CONTROL),
                Map.of(deletedDevice.getId(), AccessLevel.CONTROL)
        );

        HomeAuthorizationService authService = new HomeAuthorizationService(
                homeRepository,
                homeMemberRepository,
                memberRoomAccessRepository,
                memberDeviceAccessRepository,
                deviceRepository,
                null,
                null
        );

        AccessLevel level = authService.effectiveLevel(context, deletedDevice);
        assertEquals(AccessLevel.NONE, level);
    }
}
