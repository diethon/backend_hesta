package com.hesta.backend.service;

import com.hesta.backend.dto.request.CreateAutomationRuleRequest;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.dto.response.SceneResponse;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.impl.AutomationRuleServiceImpl;
import com.hesta.backend.service.impl.DeviceServiceImpl;
import com.hesta.backend.service.impl.HomeServiceImpl;
import com.hesta.backend.service.impl.SceneServiceImpl;
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
class Phase2RestEnforcementTest {

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @Mock
    private AutomationRuleRepository automationRuleRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private SceneRepository sceneRepository;

    @Mock
    private HomeMemberRepository homeMemberRepository;

    @Mock
    private HomeRepository homeRepository;

    @Mock
    private MemberRoomAccessRepository memberRoomAccessRepository;

    @Mock
    private MemberDeviceAccessRepository memberDeviceAccessRepository;

    @Mock
    private ManualOverrideService manualOverrideService;

    @InjectMocks
    private AutomationRuleServiceImpl automationRuleService;

    @InjectMocks
    private DeviceServiceImpl deviceService;

    @InjectMocks
    private SceneServiceImpl sceneService;

    @InjectMocks
    private HomeServiceImpl homeService;

    private UUID homeId;
    private UUID customUserId;
    private UUID ownerUserId;
    private UUID allScopeUserId;

    private Home home;
    private Room livingRoom;
    private Room bedroom;
    private Device livingLight;
    private Device bedroomFan;

    private MemberAccessContext customCtx;
    private MemberAccessContext ownerCtx;
    private MemberAccessContext allScopeCtx;

    @BeforeEach
    void setUp() {
        homeId = UUID.randomUUID();
        customUserId = UUID.randomUUID();
        ownerUserId = UUID.randomUUID();
        allScopeUserId = UUID.randomUUID();

        home = Home.builder().id(homeId).name("Test Home").build();
        livingRoom = Room.builder().id(UUID.randomUUID()).home(home).name("Living Room").build();
        bedroom = Room.builder().id(UUID.randomUUID()).home(home).name("Bedroom").build();

        livingLight = Device.builder().id(UUID.randomUUID()).room(livingRoom).name("Living Light").deviceType("LIGHT").status(DeviceStatus.ONLINE).build();
        bedroomFan = Device.builder().id(UUID.randomUUID()).room(bedroom).name("Bedroom Fan").deviceType("FAN").status(DeviceStatus.ONLINE).build();

        customCtx = new MemberAccessContext(homeId, customUserId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                AccessScope.CUSTOM, Map.of(livingRoom.getId(), AccessLevel.CONTROL, bedroom.getId(), AccessLevel.NONE), Collections.emptyMap());

        ownerCtx = new MemberAccessContext(homeId, ownerUserId, UUID.randomUUID(), HomeRole.OWNER, MemberStatus.ACTIVE,
                AccessScope.ALL, Collections.emptyMap(), Collections.emptyMap());

        allScopeCtx = new MemberAccessContext(homeId, allScopeUserId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                AccessScope.ALL, Collections.emptyMap(), Collections.emptyMap());
    }

    @Test
    @DisplayName("REST Enforcement: CUSTOM member gets 403 UNAUTHORIZED on Automation Rule APIs")
    void customMember_AutomationRuleAccess_Throws403() {
        when(homeAuthorizationService.loadAccessContext(homeId, customUserId)).thenReturn(customCtx);

        AppException ex1 = assertThrows(AppException.class, () ->
                automationRuleService.getAll(customUserId, homeId));
        assertEquals(ErrorCode.UNAUTHORIZED, ex1.getErrorCode());

        AppException ex2 = assertThrows(AppException.class, () ->
                automationRuleService.create(customUserId, homeId, CreateAutomationRuleRequest.builder().name("Rule").build()));
        assertEquals(ErrorCode.UNAUTHORIZED, ex2.getErrorCode());
    }

    @Test
    @DisplayName("REST Enforcement: OWNER and ALL-scope members can access Automation Rule APIs")
    void ownerAndAllScope_AutomationRuleAccess_Allowed() {
        when(homeAuthorizationService.loadAccessContext(homeId, ownerUserId)).thenReturn(ownerCtx);
        when(automationRuleRepository.findAllByHomeIdOrderByNameAsc(homeId)).thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> automationRuleService.getAll(ownerUserId, homeId));

        when(homeAuthorizationService.loadAccessContext(homeId, allScopeUserId)).thenReturn(allScopeCtx);
        assertDoesNotThrow(() -> automationRuleService.getAll(allScopeUserId, homeId));
    }

    @Test
    @DisplayName("REST Enforcement: CUSTOM member sees only accessible devices (omits NONE)")
    void customMember_GetDevicesByHome_FiltersOutNoneLevel() {
        when(homeAuthorizationService.loadAccessContext(homeId, customUserId)).thenReturn(customCtx);
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(livingLight, bedroomFan));
        when(homeAuthorizationService.effectiveLevel(customCtx, livingLight)).thenReturn(AccessLevel.CONTROL);
        when(homeAuthorizationService.effectiveLevel(customCtx, bedroomFan)).thenReturn(AccessLevel.NONE);

        List<DeviceResponse> responses = deviceService.getDevicesByHome(customUserId, homeId);

        assertEquals(1, responses.size());
        assertEquals(livingLight.getId(), responses.get(0).getId());
        assertEquals(AccessLevel.CONTROL, responses.get(0).getAccessLevel());
    }

    @Test
    @DisplayName("REST Enforcement: CUSTOM member getDeviceDetail on NONE device throws 404 DEVICE_NOT_FOUND")
    void customMember_GetDeviceDetail_NoneLevel_Throws404() {
        when(deviceRepository.findById(bedroomFan.getId())).thenReturn(Optional.of(bedroomFan));
        doThrow(new AppException(ErrorCode.DEVICE_NOT_FOUND))
                .when(homeAuthorizationService).requireDeviceAccess(customUserId, bedroomFan.getId(), AccessLevel.VIEW);

        AppException ex = assertThrows(AppException.class, () ->
                deviceService.getDeviceDetail(customUserId, bedroomFan.getId()));
        assertEquals(ErrorCode.DEVICE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("REST Enforcement: CUSTOM member scene list filters out scenes with inaccessible devices")
    void customMember_GetScenes_FiltersInaccessibleScenes() {
        Scene accessibleScene = Scene.builder().id(UUID.randomUUID()).home(home).name("Living Light Scene").build();
        Scene inaccessibleScene = Scene.builder().id(UUID.randomUUID()).home(home).name("Bedroom Fan Scene").build();

        when(homeAuthorizationService.loadAccessContext(homeId, customUserId)).thenReturn(customCtx);
        when(sceneRepository.findAllByHomeIdOrderByNameAsc(homeId)).thenReturn(List.of(accessibleScene, inaccessibleScene));
        when(homeAuthorizationService.canRunScene(customUserId, accessibleScene.getId())).thenReturn(true);
        when(homeAuthorizationService.canRunScene(customUserId, inaccessibleScene.getId())).thenReturn(false);

        List<SceneResponse> responses = sceneService.getScenesForHome(customUserId, homeId);

        assertEquals(1, responses.size());
        assertEquals("Living Light Scene", responses.get(0).getName());
    }

    @Test
    @DisplayName("REST Enforcement: CUSTOM member querying members list has emails masked/omitted (Product Decision 6)")
    void customMember_GetHomeMembers_OmitsEmailsForOtherMembers() {
        User otherUser = User.builder().id(UUID.randomUUID()).fullName("Jane Doe").email("jane@hesta.vn").build();
        HomeMember otherMember = HomeMember.builder().id(UUID.randomUUID()).home(home).user(otherUser).role(HomeRole.MEMBER).status(MemberStatus.ACTIVE).build();

        User callerUser = User.builder().id(customUserId).fullName("Custom User").email("custom@hesta.vn").build();
        HomeMember callerMember = HomeMember.builder().id(UUID.randomUUID()).home(home).user(callerUser).role(HomeRole.MEMBER).accessScope(AccessScope.CUSTOM).status(MemberStatus.ACTIVE).build();

        when(homeMemberRepository.findByHomeIdAndUserId(homeId, customUserId)).thenReturn(Optional.of(callerMember));
        when(homeMemberRepository.findByHomeIdWithUser(homeId)).thenReturn(List.of(callerMember, otherMember));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) homeService.getHomeMembers(customUserId, homeId);

        assertEquals(2, members.size());
        Map<String, Object> otherRes = members.stream()
                .filter(m -> !m.get("id").equals(customUserId))
                .findFirst().orElseThrow();
        assertFalse(otherRes.containsKey("email"), "CUSTOM member must not see other members' emails");
    }
}
