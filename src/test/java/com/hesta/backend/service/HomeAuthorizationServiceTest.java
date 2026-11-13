package com.hesta.backend.service;

import com.hesta.backend.entity.*;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HomeAuthorizationServiceTest {

    @Mock
    private HomeRepository homeRepository;
    @Mock
    private HomeMemberRepository homeMemberRepository;
    @Mock
    private MemberRoomAccessRepository memberRoomAccessRepository;
    @Mock
    private MemberDeviceAccessRepository memberDeviceAccessRepository;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private SceneRepository sceneRepository;
    @Mock
    private DeviceGroupRepository deviceGroupRepository;

    @InjectMocks
    private HomeAuthorizationService authorizationService;

    private UUID homeId;
    private UUID userId;
    private Home home;
    private User user;
    private Room livingRoom;
    private Room bedroom;
    private Device livingRoomLight;
    private Device bedroomFan;
    private Device unassignedDevice;

    @BeforeEach
    void setUp() {
        homeId = UUID.randomUUID();
        userId = UUID.randomUUID();

        home = Home.builder().id(homeId).name("Test Home").build();
        user = User.builder().id(userId).fullName("Test User").build();

        livingRoom = Room.builder().id(UUID.randomUUID()).home(home).name("Living Room").build();
        bedroom = Room.builder().id(UUID.randomUUID()).home(home).name("Bedroom").build();

        livingRoomLight = Device.builder()
                .id(UUID.randomUUID())
                .name("Living Room Light")
                .room(livingRoom)
                .isDeleted(false)
                .build();

        bedroomFan = Device.builder()
                .id(UUID.randomUUID())
                .name("Bedroom Fan")
                .room(bedroom)
                .isDeleted(false)
                .build();

        unassignedDevice = Device.builder()
                .id(UUID.randomUUID())
                .name("Unassigned Sensor")
                .room(null)
                .isDeleted(false)
                .build();
    }

    @Nested
    @DisplayName("Effective Access Level Tests")
    class EffectiveLevelTests {

        @Test
        @DisplayName("1. Owner always has CONTROL level")
        void ownerHasControlLevel() {
            MemberAccessContext context = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.OWNER, MemberStatus.ACTIVE,
                    AccessScope.ALL, Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(authorizationService.effectiveLevel(context, livingRoomLight)).isEqualTo(AccessLevel.CONTROL);
            assertThat(authorizationService.effectiveLevel(context, unassignedDevice)).isEqualTo(AccessLevel.CONTROL);
        }

        @Test
        @DisplayName("2. Member with scope ALL always has CONTROL level")
        void scopeAllHasControlLevel() {
            MemberAccessContext context = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                    AccessScope.ALL, Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(authorizationService.effectiveLevel(context, livingRoomLight)).isEqualTo(AccessLevel.CONTROL);
            assertThat(authorizationService.effectiveLevel(context, unassignedDevice)).isEqualTo(AccessLevel.CONTROL);
        }

        @Test
        @DisplayName("3. CUSTOM member inherits room level (VIEW, CONTROL, NONE)")
        void customMemberInheritsRoomLevel() {
            Map<UUID, AccessLevel> roomLevels = Map.of(
                    livingRoom.getId(), AccessLevel.CONTROL,
                    bedroom.getId(), AccessLevel.VIEW
            );

            MemberAccessContext context = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                    AccessScope.CUSTOM, roomLevels, Collections.emptyMap()
            );

            // Living room light inherits CONTROL from living room
            assertThat(authorizationService.effectiveLevel(context, livingRoomLight)).isEqualTo(AccessLevel.CONTROL);
            // Bedroom fan inherits VIEW from bedroom
            assertThat(authorizationService.effectiveLevel(context, bedroomFan)).isEqualTo(AccessLevel.VIEW);
        }

        @Test
        @DisplayName("4. Device override: room CONTROL + device NONE -> device is NONE")
        void deviceOverrideHidesDeviceInControlledRoom() {
            Map<UUID, AccessLevel> roomLevels = Map.of(livingRoom.getId(), AccessLevel.CONTROL);
            Map<UUID, AccessLevel> deviceOverrides = Map.of(livingRoomLight.getId(), AccessLevel.NONE);

            MemberAccessContext context = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                    AccessScope.CUSTOM, roomLevels, deviceOverrides
            );

            assertThat(authorizationService.effectiveLevel(context, livingRoomLight)).isEqualTo(AccessLevel.NONE);
        }

        @Test
        @DisplayName("5. Device override: room NONE + device VIEW -> device is VIEW")
        void deviceOverrideGrantsAccessInHiddenRoom() {
            Map<UUID, AccessLevel> roomLevels = Map.of(bedroom.getId(), AccessLevel.NONE);
            Map<UUID, AccessLevel> deviceOverrides = Map.of(bedroomFan.getId(), AccessLevel.VIEW);

            MemberAccessContext context = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                    AccessScope.CUSTOM, roomLevels, deviceOverrides
            );

            assertThat(authorizationService.effectiveLevel(context, bedroomFan)).isEqualTo(AccessLevel.VIEW);
        }

        @Test
        @DisplayName("6. Unassigned device without override is NONE for CUSTOM member")
        void unassignedDeviceWithoutOverrideIsNone() {
            MemberAccessContext context = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                    AccessScope.CUSTOM, Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(authorizationService.effectiveLevel(context, unassignedDevice)).isEqualTo(AccessLevel.NONE);
        }

        @Test
        @DisplayName("7. Unassigned device with override gets overridden level")
        void unassignedDeviceWithOverrideGetsLevel() {
            Map<UUID, AccessLevel> deviceOverrides = Map.of(unassignedDevice.getId(), AccessLevel.CONTROL);

            MemberAccessContext context = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                    AccessScope.CUSTOM, Collections.emptyMap(), deviceOverrides
            );

            assertThat(authorizationService.effectiveLevel(context, unassignedDevice)).isEqualTo(AccessLevel.CONTROL);
        }

        @Test
        @DisplayName("8. Soft-deleted device is always NONE")
        void softDeletedDeviceIsAlwaysNone() {
            Device deletedDevice = Device.builder()
                    .id(UUID.randomUUID())
                    .name("Deleted Device")
                    .room(livingRoom)
                    .isDeleted(true)
                    .build();

            MemberAccessContext ownerContext = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.OWNER, MemberStatus.ACTIVE,
                    AccessScope.ALL, Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(authorizationService.effectiveLevel(ownerContext, deletedDevice)).isEqualTo(AccessLevel.NONE);
        }

        @Test
        @DisplayName("9. Member with status != ACTIVE has NONE on all devices")
        void inactiveMemberHasNone() {
            MemberAccessContext suspendedContext = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.DISABLED,
                    AccessScope.ALL, Collections.emptyMap(), Collections.emptyMap()
            );

            assertThat(authorizationService.effectiveLevel(suspendedContext, livingRoomLight)).isEqualTo(AccessLevel.NONE);
        }

        @Test
        @DisplayName("10. Member promoted to OWNER gets CONTROL level on all devices")
        void memberPromotedToOwnerGetsControl() {
            // Prior: CUSTOM member with NONE
            MemberAccessContext customContext = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                    AccessScope.CUSTOM, Collections.emptyMap(), Collections.emptyMap()
            );
            assertThat(authorizationService.effectiveLevel(customContext, livingRoomLight)).isEqualTo(AccessLevel.NONE);

            // Promoted to OWNER
            MemberAccessContext ownerContext = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.OWNER, MemberStatus.ACTIVE,
                    AccessScope.ALL, Collections.emptyMap(), Collections.emptyMap()
            );
            assertThat(authorizationService.effectiveLevel(ownerContext, livingRoomLight)).isEqualTo(AccessLevel.CONTROL);
        }

        @Test
        @DisplayName("11. Room deleted leaves device unassigned (effective level becomes NONE)")
        void roomDeletionLeavesDeviceUnassigned() {
            Map<UUID, AccessLevel> roomLevels = Map.of(livingRoom.getId(), AccessLevel.CONTROL);
            MemberAccessContext context = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                    AccessScope.CUSTOM, roomLevels, Collections.emptyMap()
            );

            assertThat(authorizationService.effectiveLevel(context, livingRoomLight)).isEqualTo(AccessLevel.CONTROL);

            // Room deleted -> device room set to null
            livingRoomLight.setRoom(null);
            assertThat(authorizationService.effectiveLevel(context, livingRoomLight)).isEqualTo(AccessLevel.NONE);
        }

        @Test
        @DisplayName("12. Device moved between rooms changes effective level")
        void deviceMovedBetweenRoomsChangesLevel() {
            Map<UUID, AccessLevel> roomLevels = Map.of(
                    livingRoom.getId(), AccessLevel.CONTROL,
                    bedroom.getId(), AccessLevel.VIEW
            );

            MemberAccessContext context = new MemberAccessContext(
                    homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                    AccessScope.CUSTOM, roomLevels, Collections.emptyMap()
            );

            // In living room: CONTROL
            assertThat(authorizationService.effectiveLevel(context, livingRoomLight)).isEqualTo(AccessLevel.CONTROL);

            // Moved to bedroom: becomes VIEW
            livingRoomLight.setRoom(bedroom);
            assertThat(authorizationService.effectiveLevel(context, livingRoomLight)).isEqualTo(AccessLevel.VIEW);
        }

        @Test
        @DisplayName("13. Missing context returns NONE")
        void missingContextReturnsNone() {
            assertThat(authorizationService.effectiveLevel(null, livingRoomLight)).isEqualTo(AccessLevel.NONE);
        }
    }

    @Nested
    @DisplayName("requireDeviceAccess Tests")
    class RequireDeviceAccessTests {

        @Test
        @DisplayName("Hidden device (level NONE) throws 404 DEVICE_NOT_FOUND (fail closed)")
        void hiddenDeviceThrows404() {
            when(deviceRepository.findById(livingRoomLight.getId())).thenReturn(Optional.of(livingRoomLight));
            HomeMember member = HomeMember.builder()
                    .home(home)
                    .user(user)
                    .role(HomeRole.MEMBER)
                    .status(MemberStatus.ACTIVE)
                    .accessScope(AccessScope.CUSTOM)
                    .build();
            when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Optional.of(member));
            when(memberRoomAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Collections.emptyList());
            when(memberDeviceAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Collections.emptyList());

            assertThatThrownBy(() -> authorizationService.requireDeviceAccess(userId, livingRoomLight.getId(), AccessLevel.VIEW))
                    .isInstanceOf(AppException.class)
                    .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.DEVICE_NOT_FOUND));
        }

        @Test
        @DisplayName("Visible device with VIEW level throws 403 UNAUTHORIZED when requesting CONTROL")
        void insufficientLevelThrows403() {
            when(deviceRepository.findById(livingRoomLight.getId())).thenReturn(Optional.of(livingRoomLight));
            HomeMember member = HomeMember.builder()
                    .home(home)
                    .user(user)
                    .role(HomeRole.MEMBER)
                    .status(MemberStatus.ACTIVE)
                    .accessScope(AccessScope.CUSTOM)
                    .build();
            when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Optional.of(member));

            MemberRoomAccess roomAccess = MemberRoomAccess.builder()
                    .member(member)
                    .room(livingRoom)
                    .accessLevel(AccessLevel.VIEW)
                    .build();
            when(memberRoomAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(List.of(roomAccess));
            when(memberDeviceAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Collections.emptyList());

            assertThatThrownBy(() -> authorizationService.requireDeviceAccess(userId, livingRoomLight.getId(), AccessLevel.CONTROL))
                    .isInstanceOf(AppException.class)
                    .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        }

        @Test
        @DisplayName("Sufficient level succeeds and returns Device")
        void sufficientLevelSucceeds() {
            when(deviceRepository.findById(livingRoomLight.getId())).thenReturn(Optional.of(livingRoomLight));
            HomeMember member = HomeMember.builder()
                    .home(home)
                    .user(user)
                    .role(HomeRole.MEMBER)
                    .status(MemberStatus.ACTIVE)
                    .accessScope(AccessScope.CUSTOM)
                    .build();
            when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Optional.of(member));

            MemberRoomAccess roomAccess = MemberRoomAccess.builder()
                    .member(member)
                    .room(livingRoom)
                    .accessLevel(AccessLevel.CONTROL)
                    .build();
            when(memberRoomAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(List.of(roomAccess));
            when(memberDeviceAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Collections.emptyList());

            Device result = authorizationService.requireDeviceAccess(userId, livingRoomLight.getId(), AccessLevel.CONTROL);
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(livingRoomLight.getId());
        }
    }

    @Nested
    @DisplayName("canRunScene Tests")
    class CanRunSceneTests {

        private UUID sceneId;

        @BeforeEach
        void initScene() {
            sceneId = UUID.randomUUID();
        }

        @Test
        @DisplayName("CUSTOM member can run scene if they have CONTROL on all live devices")
        void canRunSceneWhenAllDevicesControlled() {
            SceneAction action1 = SceneAction.builder().targetDevice(livingRoomLight).build();
            SceneAction action2 = SceneAction.builder().targetDevice(bedroomFan).build();
            Scene scene = Scene.builder().id(sceneId).home(home).actions(List.of(action1, action2)).build();

            when(sceneRepository.findById(sceneId)).thenReturn(Optional.of(scene));

            HomeMember member = HomeMember.builder()
                    .home(home)
                    .user(user)
                    .role(HomeRole.MEMBER)
                    .status(MemberStatus.ACTIVE)
                    .accessScope(AccessScope.CUSTOM)
                    .build();
            when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Optional.of(member));

            MemberRoomAccess ra1 = MemberRoomAccess.builder().room(livingRoom).accessLevel(AccessLevel.CONTROL).build();
            MemberRoomAccess ra2 = MemberRoomAccess.builder().room(bedroom).accessLevel(AccessLevel.CONTROL).build();
            when(memberRoomAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(List.of(ra1, ra2));
            when(memberDeviceAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Collections.emptyList());

            assertThat(authorizationService.canRunScene(userId, sceneId)).isTrue();
        }

        @Test
        @DisplayName("CUSTOM member cannot run scene if any live device is VIEW only")
        void cannotRunSceneWhenOneDeviceIsViewOnly() {
            SceneAction action1 = SceneAction.builder().targetDevice(livingRoomLight).build();
            SceneAction action2 = SceneAction.builder().targetDevice(bedroomFan).build();
            Scene scene = Scene.builder().id(sceneId).home(home).actions(List.of(action1, action2)).build();

            when(sceneRepository.findById(sceneId)).thenReturn(Optional.of(scene));

            HomeMember member = HomeMember.builder()
                    .home(home)
                    .user(user)
                    .role(HomeRole.MEMBER)
                    .status(MemberStatus.ACTIVE)
                    .accessScope(AccessScope.CUSTOM)
                    .build();
            when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Optional.of(member));

            // Living room CONTROL, bedroom VIEW
            MemberRoomAccess ra1 = MemberRoomAccess.builder().room(livingRoom).accessLevel(AccessLevel.CONTROL).build();
            MemberRoomAccess ra2 = MemberRoomAccess.builder().room(bedroom).accessLevel(AccessLevel.VIEW).build();
            when(memberRoomAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(List.of(ra1, ra2));
            when(memberDeviceAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Collections.emptyList());

            assertThat(authorizationService.canRunScene(userId, sceneId)).isFalse();
        }

        @Test
        @DisplayName("Scene with no live devices returns false for CUSTOM member (Product Decision 2)")
        void sceneWithNoLiveDevicesReturnsFalse() {
            Device deletedDevice = Device.builder().id(UUID.randomUUID()).name("Old Light").isDeleted(true).build();
            SceneAction action = SceneAction.builder().targetDevice(deletedDevice).build();
            Scene scene = Scene.builder().id(sceneId).home(home).actions(List.of(action)).build();

            when(sceneRepository.findById(sceneId)).thenReturn(Optional.of(scene));

            HomeMember member = HomeMember.builder()
                    .home(home)
                    .user(user)
                    .role(HomeRole.MEMBER)
                    .status(MemberStatus.ACTIVE)
                    .accessScope(AccessScope.CUSTOM)
                    .build();
            when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Optional.of(member));
            when(memberRoomAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Collections.emptyList());
            when(memberDeviceAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Collections.emptyList());

            assertThat(authorizationService.canRunScene(userId, sceneId)).isFalse();
        }
    }

    @Nested
    @DisplayName("canControlGroup Tests")
    class CanControlGroupTests {

        private UUID groupId;

        @BeforeEach
        void initGroup() {
            groupId = UUID.randomUUID();
        }

        @Test
        @DisplayName("CUSTOM member can control group if they have CONTROL on all devices")
        void canControlGroupWhenAllControlled() {
            DeviceGroup group = DeviceGroup.builder()
                    .id(groupId)
                    .home(home)
                    .devices(List.of(livingRoomLight, bedroomFan))
                    .build();
            when(deviceGroupRepository.findById(groupId)).thenReturn(Optional.of(group));

            HomeMember member = HomeMember.builder()
                    .home(home)
                    .user(user)
                    .role(HomeRole.MEMBER)
                    .status(MemberStatus.ACTIVE)
                    .accessScope(AccessScope.CUSTOM)
                    .build();
            when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Optional.of(member));

            MemberRoomAccess ra1 = MemberRoomAccess.builder().room(livingRoom).accessLevel(AccessLevel.CONTROL).build();
            MemberRoomAccess ra2 = MemberRoomAccess.builder().room(bedroom).accessLevel(AccessLevel.CONTROL).build();
            when(memberRoomAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(List.of(ra1, ra2));
            when(memberDeviceAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Collections.emptyList());

            assertThat(authorizationService.canControlGroup(userId, groupId)).isTrue();
        }

        @Test
        @DisplayName("CUSTOM member cannot control group if any device is not CONTROL")
        void cannotControlGroupWhenOneDeviceIsNotControl() {
            DeviceGroup group = DeviceGroup.builder()
                    .id(groupId)
                    .home(home)
                    .devices(List.of(livingRoomLight, bedroomFan))
                    .build();
            when(deviceGroupRepository.findById(groupId)).thenReturn(Optional.of(group));

            HomeMember member = HomeMember.builder()
                    .home(home)
                    .user(user)
                    .role(HomeRole.MEMBER)
                    .status(MemberStatus.ACTIVE)
                    .accessScope(AccessScope.CUSTOM)
                    .build();
            when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Optional.of(member));

            MemberRoomAccess ra1 = MemberRoomAccess.builder().room(livingRoom).accessLevel(AccessLevel.CONTROL).build();
            MemberRoomAccess ra2 = MemberRoomAccess.builder().room(bedroom).accessLevel(AccessLevel.NONE).build();
            when(memberRoomAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(List.of(ra1, ra2));
            when(memberDeviceAccessRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Collections.emptyList());

            assertThat(authorizationService.canControlGroup(userId, groupId)).isFalse();
        }
    }
}
