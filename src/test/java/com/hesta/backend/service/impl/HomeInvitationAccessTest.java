package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.access.CreateInvitationRequest;
import com.hesta.backend.dto.access.MemberDeviceAccessDto;
import com.hesta.backend.dto.access.MemberRoomAccessDto;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.InvitationStatus;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HomeInvitationAccessTest {

    @Mock
    private HomeRepository homeRepository;

    @Mock
    private HomeMemberRepository homeMemberRepository;

    @Mock
    private HomeInvitationRepository homeInvitationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private MemberRoomAccessRepository memberRoomAccessRepository;

    @Mock
    private MemberDeviceAccessRepository memberDeviceAccessRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private HomeServiceImpl homeService;

    private UUID homeId;
    private UUID inviterId;
    private UUID inviteeUserId;

    private Home home;
    private User inviter;
    private User inviteeUser;
    private HomeMember ownerMember;

    private Room livingRoom;
    private Device light1;

    @BeforeEach
    void setUp() {
        homeId = UUID.randomUUID();
        inviterId = UUID.randomUUID();
        inviteeUserId = UUID.randomUUID();

        home = Home.builder().id(homeId).name("Nhà Test").build();
        inviter = User.builder().id(inviterId).fullName("Chủ nhà").email("owner@hesta.vn").build();
        inviteeUser = User.builder().id(inviteeUserId).fullName("Người được mời").email("guest@hesta.vn").build();

        ownerMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(inviter)
                .role(HomeRole.OWNER)
                .status(MemberStatus.ACTIVE)
                .build();

        livingRoom = Room.builder().id(UUID.randomUUID()).home(home).name("Phòng Khách").build();
        light1 = Device.builder().id(UUID.randomUUID()).room(livingRoom).name("Đèn").isDeleted(false).build();

        ReflectionTestUtils.setField(homeService, "customScopeEnabled", true);
    }

    @Test
    @DisplayName("Invitation - Legacy ?email= without body defaults to scope ALL")
    void testGenerateInvitation_LegacyQueryParam_DefaultsToAll() {
        when(userRepository.findById(inviterId)).thenReturn(Optional.of(inviter));
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, inviterId)).thenReturn(Optional.of(ownerMember));
        when(userRepository.findByEmail("guest@hesta.vn")).thenReturn(Optional.empty());
        when(homeInvitationRepository.findByHomeIdAndTargetEmailAndStatus(eq(homeId), eq("guest@hesta.vn"), eq(InvitationStatus.PENDING)))
                .thenReturn(List.of());
        when(emailService.sendInvitationEmail(any(), any(), any(), any(), any())).thenReturn(true);

        Map<String, Object> res = homeService.generateInvitation(inviterId, homeId, "guest@hesta.vn", null);

        assertNotNull(res);
        assertEquals("ALL", res.get("accessScope"));
        assertEquals(true, res.get("emailSent"));

        ArgumentCaptor<HomeInvitation> captor = ArgumentCaptor.forClass(HomeInvitation.class);
        verify(homeInvitationRepository).save(captor.capture());
        assertEquals(AccessScope.ALL, captor.getValue().getAccessScope());
        assertNull(captor.getValue().getAccessPayload());
    }

    @Test
    @DisplayName("Invitation - Body with CUSTOM + flag on stores normalized payload")
    void testGenerateInvitation_BodyWithCustom_FlagOn_StoresPayload() {
        when(userRepository.findById(inviterId)).thenReturn(Optional.of(inviter));
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, inviterId)).thenReturn(Optional.of(ownerMember));
        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1));

        CreateInvitationRequest req = CreateInvitationRequest.builder()
                .access(CreateInvitationRequest.InvitationAccessPayload.builder()
                        .scope(AccessScope.CUSTOM)
                        .rooms(List.of(MemberRoomAccessDto.builder().roomId(livingRoom.getId()).level(AccessLevel.CONTROL).build()))
                        .devices(List.of(MemberDeviceAccessDto.builder().deviceId(light1.getId()).level(AccessLevel.VIEW).build()))
                        .build())
                .build();

        Map<String, Object> res = homeService.generateInvitation(inviterId, homeId, null, req);

        assertNotNull(res);
        assertEquals("CUSTOM", res.get("accessScope"));
        assertEquals(false, res.get("emailSent")); // No email passed

        ArgumentCaptor<HomeInvitation> captor = ArgumentCaptor.forClass(HomeInvitation.class);
        verify(homeInvitationRepository).save(captor.capture());
        assertEquals(AccessScope.CUSTOM, captor.getValue().getAccessScope());
        assertNotNull(captor.getValue().getAccessPayload());
        assertTrue(captor.getValue().getAccessPayload().contains(livingRoom.getId().toString()));
    }

    @Test
    @DisplayName("Invitation - Body with CUSTOM + flag off throws 409 FEATURE_DISABLED")
    void testGenerateInvitation_CustomFlagOff_ThrowsFeatureDisabled() {
        ReflectionTestUtils.setField(homeService, "customScopeEnabled", false);

        when(userRepository.findById(inviterId)).thenReturn(Optional.of(inviter));
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, inviterId)).thenReturn(Optional.of(ownerMember));

        CreateInvitationRequest req = CreateInvitationRequest.builder()
                .access(CreateInvitationRequest.InvitationAccessPayload.builder()
                        .scope(AccessScope.CUSTOM)
                        .build())
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                homeService.generateInvitation(inviterId, homeId, null, req));
        assertEquals(ErrorCode.FEATURE_DISABLED, ex.getErrorCode());
    }

    @Test
    @DisplayName("Invitation - Already a member throws 409 MEMBER_ALREADY_EXISTS")
    void testGenerateInvitation_AlreadyMember_ThrowsConflict() {
        when(userRepository.findById(inviterId)).thenReturn(Optional.of(inviter));
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, inviterId)).thenReturn(Optional.of(ownerMember));
        when(userRepository.findByEmail("guest@hesta.vn")).thenReturn(Optional.of(inviteeUser));
        when(homeMemberRepository.existsByHomeIdAndUserId(homeId, inviteeUserId)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () ->
                homeService.generateInvitation(inviterId, homeId, "guest@hesta.vn", null));
        assertEquals(ErrorCode.MEMBER_ALREADY_EXISTS, ex.getErrorCode());
    }

    @Test
    @DisplayName("Invitation - Already invited (pending active) cancels previous and creates new invite")
    void testGenerateInvitation_AlreadyInvited_CancelsPreviousAndCreatesNew() {
        when(userRepository.findById(inviterId)).thenReturn(Optional.of(inviter));
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, inviterId)).thenReturn(Optional.of(ownerMember));
        when(userRepository.findByEmail("guest@hesta.vn")).thenReturn(Optional.empty());
        when(emailService.sendInvitationEmail(any(), any(), any(), any(), any())).thenReturn(true);

        HomeInvitation activeInvite = HomeInvitation.builder()
                .expiresAt(OffsetDateTime.now().plusDays(2))
                .status(InvitationStatus.PENDING)
                .build();
        when(homeInvitationRepository.findByHomeIdAndTargetEmailAndStatus(eq(homeId), eq("guest@hesta.vn"), eq(InvitationStatus.PENDING)))
                .thenReturn(List.of(activeInvite));

        Map<String, Object> res = homeService.generateInvitation(inviterId, homeId, "guest@hesta.vn", null);

        assertNotNull(res);
        assertEquals(InvitationStatus.CANCELLED, activeInvite.getStatus());
        verify(homeInvitationRepository).saveAll(argThat((Iterable<HomeInvitation> list) -> {
            var it = list.iterator();
            return it.hasNext() && it.next().getStatus() == InvitationStatus.CANCELLED;
        }));
        verify(homeInvitationRepository).save(any(HomeInvitation.class));
    }

    @Test
    @DisplayName("Join - applies stored CUSTOM payload in same transaction")
    void testJoinHome_AppliesPayload() {
        when(userRepository.findById(inviteeUserId)).thenReturn(Optional.of(inviteeUser));

        String payload = "{\"rooms\":[{\"roomId\":\"" + livingRoom.getId() + "\",\"accessLevel\":\"CONTROL\"}]," +
                "\"devices\":[{\"deviceId\":\"" + light1.getId() + "\",\"accessLevel\":\"VIEW\"}]}";

        HomeInvitation invitation = HomeInvitation.builder()
                .home(home)
                .inviter(inviter)
                .inviteToken("valid-token")
                .expiresAt(OffsetDateTime.now().plusDays(5))
                .status(InvitationStatus.PENDING)
                .accessScope(AccessScope.CUSTOM)
                .accessPayload(payload)
                .build();

        when(homeInvitationRepository.findByInviteCode("valid-token")).thenReturn(Optional.empty());
        when(homeInvitationRepository.findByInviteToken("valid-token")).thenReturn(Optional.of(invitation));
        when(homeMemberRepository.existsByHomeIdAndUserId(homeId, inviteeUserId)).thenReturn(false);
        when(homeMemberRepository.save(any(HomeMember.class))).thenAnswer(invocationOnMock -> invocationOnMock.getArgument(0));

        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1));

        homeService.joinHome(inviteeUserId, "valid-token");

        assertEquals(InvitationStatus.ACCEPTED, invitation.getStatus());
        verify(memberRoomAccessRepository).save(any(MemberRoomAccess.class));
        verify(memberDeviceAccessRepository).save(any(MemberDeviceAccess.class));
    }

    @Test
    @DisplayName("Join - flag off falls back to ALL and logs warning without failing")
    void testJoinHome_FlagOff_FallsBackToAll() {
        ReflectionTestUtils.setField(homeService, "customScopeEnabled", false);
        when(userRepository.findById(inviteeUserId)).thenReturn(Optional.of(inviteeUser));

        HomeInvitation invitation = HomeInvitation.builder()
                .home(home)
                .inviter(inviter)
                .inviteToken("token-custom")
                .expiresAt(OffsetDateTime.now().plusDays(5))
                .status(InvitationStatus.PENDING)
                .accessScope(AccessScope.CUSTOM)
                .accessPayload("{\"rooms\":[],\"devices\":[]}")
                .build();

        when(homeInvitationRepository.findByInviteCode("token-custom")).thenReturn(Optional.empty());
        when(homeInvitationRepository.findByInviteToken("token-custom")).thenReturn(Optional.of(invitation));
        when(homeMemberRepository.existsByHomeIdAndUserId(homeId, inviteeUserId)).thenReturn(false);

        ArgumentCaptor<HomeMember> memberCaptor = ArgumentCaptor.forClass(HomeMember.class);
        when(homeMemberRepository.save(memberCaptor.capture())).thenAnswer(invocationOnMock -> invocationOnMock.getArgument(0));

        homeService.joinHome(inviteeUserId, "token-custom");

        assertEquals(AccessScope.ALL, memberCaptor.getValue().getAccessScope());
        assertEquals(InvitationStatus.ACCEPTED, invitation.getStatus());
        verify(memberRoomAccessRepository, never()).save(any());
    }

    @Test
    @DisplayName("Join - since-deleted device in payload is skipped and join succeeds")
    void testJoinHome_DeletedDeviceSkipped() {
        when(userRepository.findById(inviteeUserId)).thenReturn(Optional.of(inviteeUser));

        UUID deletedDeviceId = UUID.randomUUID();
        String payload = "{\"rooms\":[],\"devices\":[{\"deviceId\":\"" + deletedDeviceId + "\",\"accessLevel\":\"VIEW\"}]}";

        HomeInvitation invitation = HomeInvitation.builder()
                .home(home)
                .inviter(inviter)
                .inviteToken("token-deleted-dev")
                .expiresAt(OffsetDateTime.now().plusDays(5))
                .status(InvitationStatus.PENDING)
                .accessScope(AccessScope.CUSTOM)
                .accessPayload(payload)
                .build();

        when(homeInvitationRepository.findByInviteCode("token-deleted-dev")).thenReturn(Optional.empty());
        when(homeInvitationRepository.findByInviteToken("token-deleted-dev")).thenReturn(Optional.of(invitation));
        when(homeMemberRepository.existsByHomeIdAndUserId(homeId, inviteeUserId)).thenReturn(false);
        when(homeMemberRepository.save(any(HomeMember.class))).thenAnswer(invocationOnMock -> invocationOnMock.getArgument(0));

        when(roomRepository.findByHomeId(homeId)).thenReturn(List.of(livingRoom));
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(light1)); // deletedDeviceId is not live

        assertDoesNotThrow(() -> homeService.joinHome(inviteeUserId, "token-deleted-dev"));

        verify(memberDeviceAccessRepository, never()).save(any());
        assertEquals(InvitationStatus.ACCEPTED, invitation.getStatus());
    }
}
