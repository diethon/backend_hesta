package com.hesta.backend.event;

import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.dto.event.AnomalyRaisedEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.*;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import com.hesta.backend.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnomalyNotificationListenerTest {

    @Mock
    private HomeMemberRepository homeMemberRepository;

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private DeviceRepository deviceRepository;

    @InjectMocks
    private AnomalyNotificationListener listener;

    private UUID homeId;
    private UUID deviceId;
    private UUID anomalyId;
    private Device device;
    private Home home;
    private Room room;

    private User ownerUser;
    private User allScopeUser;
    private User customAllowedUser;
    private User customDeniedUser;
    private User suspendedUser;

    private HomeMember ownerMember;
    private HomeMember allScopeMember;
    private HomeMember customAllowedMember;
    private HomeMember customDeniedMember;
    private HomeMember suspendedMember;

    @BeforeEach
    void setUp() {
        homeId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        anomalyId = UUID.randomUUID();

        home = Home.builder().id(homeId).name("HESTA Smart Home").build();
        room = Room.builder().id(UUID.randomUUID()).home(home).name("Nhà bếp").build();
        device = Device.builder()
                .id(deviceId)
                .name("Cảm biến Gas Bếp")
                .deviceType("GAS_SENSOR")
                .room(room)
                .isDeleted(false)
                .build();

        // 1. OWNER
        ownerUser = User.builder().id(UUID.randomUUID()).email("owner@hesta.vn").build();
        ownerMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(ownerUser)
                .role(HomeRole.OWNER)
                .status(MemberStatus.ACTIVE)
                .accessScope(AccessScope.ALL)
                .build();

        // 2. MEMBER with scope ALL
        allScopeUser = User.builder().id(UUID.randomUUID()).email("all@hesta.vn").build();
        allScopeMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(allScopeUser)
                .role(HomeRole.MEMBER)
                .status(MemberStatus.ACTIVE)
                .accessScope(AccessScope.ALL)
                .build();

        // 3. MEMBER with scope CUSTOM and VIEW access
        customAllowedUser = User.builder().id(UUID.randomUUID()).email("custom_allowed@hesta.vn").build();
        customAllowedMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(customAllowedUser)
                .role(HomeRole.MEMBER)
                .status(MemberStatus.ACTIVE)
                .accessScope(AccessScope.CUSTOM)
                .build();

        // 4. MEMBER with scope CUSTOM but NONE access to this device
        customDeniedUser = User.builder().id(UUID.randomUUID()).email("custom_denied@hesta.vn").build();
        customDeniedMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(customDeniedUser)
                .role(HomeRole.MEMBER)
                .status(MemberStatus.ACTIVE)
                .accessScope(AccessScope.CUSTOM)
                .build();

        // 5. Member NOT active (DISABLED)
        suspendedUser = User.builder().id(UUID.randomUUID()).email("disabled@hesta.vn").build();
        suspendedMember = HomeMember.builder()
                .id(UUID.randomUUID())
                .home(home)
                .user(suspendedUser)
                .role(HomeRole.MEMBER)
                .status(MemberStatus.DISABLED)
                .accessScope(AccessScope.ALL)
                .build();
    }

    @Test
    @DisplayName("Recipients resolution: OWNER, ALL scope, and CUSTOM scope with VIEW access receive notification; non-active and denied CUSTOM do not")
    void onAnomalyRaised_Recipients_PermissionsFilteredCorrectly() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(homeMemberRepository.findByHomeIdWithUser(homeId)).thenReturn(List.of(
                ownerMember, allScopeMember, customAllowedMember, customDeniedMember, suspendedMember
        ));

        // Stub authorization for custom members
        MemberAccessContext allowedCtx = new MemberAccessContext(
                homeId, customAllowedUser.getId(), customAllowedMember.getId(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                AccessScope.CUSTOM, Collections.emptyMap(), Map.of(deviceId, AccessLevel.VIEW)
        );
        MemberAccessContext deniedCtx = new MemberAccessContext(
                homeId, customDeniedUser.getId(), customDeniedMember.getId(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                AccessScope.CUSTOM, Collections.emptyMap(), Collections.emptyMap()
        );

        when(homeAuthorizationService.loadAccessContext(homeId, customAllowedUser.getId())).thenReturn(allowedCtx);
        when(homeAuthorizationService.effectiveLevel(allowedCtx, device)).thenReturn(AccessLevel.VIEW);

        when(homeAuthorizationService.loadAccessContext(homeId, customDeniedUser.getId())).thenReturn(deniedCtx);
        when(homeAuthorizationService.effectiveLevel(deniedCtx, device)).thenReturn(AccessLevel.NONE);

        AnomalyRaisedEvent event = new AnomalyRaisedEvent(
                anomalyId,
                homeId,
                deviceId,
                device.getName(),
                AnomalyType.GAS_LEAKAGE,
                SeverityLevel.CRITICAL,
                "gas_ppm",
                "1250 ppm",
                "<= 400 ppm",
                false,
                OffsetDateTime.now()
        );

        listener.onAnomalyRaised(event);

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService, times(3)).create(captor.capture());

        List<UUID> recipientIds = captor.getAllValues().stream().map(NotificationEvent::userId).toList();
        assertThat(recipientIds).containsExactlyInAnyOrder(
                ownerUser.getId(),
                allScopeUser.getId(),
                customAllowedUser.getId()
        );
        assertThat(recipientIds).doesNotContain(customDeniedUser.getId(), suspendedUser.getId());

        NotificationEvent first = captor.getAllValues().get(0);
        assertThat(first.priority()).isEqualTo(NotificationPriority.HIGH);
        assertThat(first.sourceAnomalyId()).isEqualTo(anomalyId);
        assertThat(first.title()).contains("Cảnh báo rò rỉ khí Gas");
    }

    @Test
    @DisplayName("Failure delivering to one recipient does NOT block notification to other recipients")
    void onAnomalyRaised_OneRecipientThrowsException_DoesNotBlockOthers() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(homeMemberRepository.findByHomeIdWithUser(homeId)).thenReturn(List.of(
                ownerMember, allScopeMember
        ));

        // When sending to ownerUser, simulate failure
        doThrow(new RuntimeException("STOMP connection failed for owner"))
                .when(notificationService).create(argThat(notif -> notif != null && ownerUser.getId().equals(notif.userId())));

        AnomalyRaisedEvent event = new AnomalyRaisedEvent(
                anomalyId,
                homeId,
                deviceId,
                device.getName(),
                AnomalyType.SMOKE_DETECTED,
                SeverityLevel.HIGH,
                "smoke",
                "350 ppm",
                "<= 300 ppm",
                false,
                OffsetDateTime.now()
        );

        // Does not throw
        listener.onAnomalyRaised(event);

        // allScopeUser MUST still receive the notification!
        verify(notificationService, times(1)).create(argThat(notif -> notif != null && allScopeUser.getId().equals(notif.userId())));
    }

    @Test
    @DisplayName("Handles safely when device, room name, or device name are null")
    void onAnomalyRaised_NullDeviceAndRoomNames_SafeFallbackUsed() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.empty()); // device not found
        when(homeMemberRepository.findByHomeIdWithUser(homeId)).thenReturn(List.of(ownerMember));

        AnomalyRaisedEvent event = new AnomalyRaisedEvent(
                anomalyId,
                homeId,
                deviceId,
                null, // null device name
                AnomalyType.SENSOR_OFFLINE,
                SeverityLevel.MEDIUM,
                "heartbeat",
                "OFFLINE",
                "<= 5m",
                false,
                OffsetDateTime.now()
        );

        listener.onAnomalyRaised(event);

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService, times(1)).create(captor.capture());

        NotificationEvent notif = captor.getValue();
        assertThat(notif.title()).isEqualTo("Cảnh báo cảm biến mất kết nối!");
        // Room name should fallback to "Khu vực trong nhà" and device name to "Cảm biến an toàn"
        assertThat(notif.message()).contains("Cảm biến an toàn");
        assertThat(notif.message()).contains("Khu vực trong nhà");
        assertThat(notif.priority()).isEqualTo(NotificationPriority.MEDIUM);
    }

    @Test
    @DisplayName("Regression: Eager device loading with room prevents LazyInitializationException and generates notification")
    void onAnomalyRaised_DeviceWithEagerRoom_NotificationCreatedSuccessfully() {
        Room mockRoom = mock(Room.class);
        when(mockRoom.getName()).thenReturn("Phòng khách");

        Device deviceWithRoom = Device.builder()
                .id(deviceId)
                .name("Cảm biến nhiệt độ")
                .deviceType("TEMP_SENSOR")
                .room(mockRoom)
                .isDeleted(false)
                .build();

        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(deviceWithRoom));
        when(homeMemberRepository.findByHomeIdWithUser(homeId)).thenReturn(List.of(ownerMember));

        AnomalyRaisedEvent event = new AnomalyRaisedEvent(
                anomalyId,
                homeId,
                deviceId,
                "Cảm biến nhiệt độ",
                AnomalyType.HIGH_TEMPERATURE,
                SeverityLevel.HIGH,
                "temperature",
                "55.0 °C",
                "<= 50.0 °C",
                false,
                OffsetDateTime.now()
        );

        listener.onAnomalyRaised(event);

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService, times(1)).create(captor.capture());

        NotificationEvent notif = captor.getValue();
        assertThat(notif.userId()).isEqualTo(ownerUser.getId());
        assertThat(notif.message()).contains("Phòng khách");
        assertThat(notif.message()).contains("Cảm biến nhiệt độ");
    }
}
