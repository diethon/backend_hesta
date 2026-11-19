package com.hesta.backend.service;

import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.SecurityEvent;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.enums.SecurityEventStatus;
import com.hesta.backend.enums.SecurityEventType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.SecurityEventRepository;
import com.hesta.backend.service.impl.SecurityEventServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityEventServiceImplTest {

    @Mock
    private SecurityEventRepository securityEventRepository;
    @Mock
    private HomeMemberRepository homeMemberRepository;
    @Mock
    private HomeAuthorizationService homeAuthorizationService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private SecurityEventServiceImpl service;

    private Home home;
    private Device motionSensor;
    private User owner;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(UUID.randomUUID()).email("owner@example.com").fullName("Owner").build();
        home = Home.builder().id(UUID.randomUUID()).name("Home").createdBy(owner).build();
        Room room = Room.builder().id(UUID.randomUUID()).name("Phòng khách").home(home).build();
        motionSensor = Device.builder().id(UUID.randomUUID()).name("Cảm biến cửa").deviceType("MOTION_SENSOR")
                .room(room).build();
    }

    @Test
    void recordMotion_outsideCooldown_savesEventAndNotifiesOwner() {
        when(securityEventRepository.existsByDeviceIdAndDetectedAtAfter(eq(motionSensor.getId()), any()))
                .thenReturn(false);
        when(securityEventRepository.save(any(SecurityEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(homeMemberRepository.findByHomeIdWithUser(home.getId())).thenReturn(List.of(
                HomeMember.builder().home(home).user(owner).role(HomeRole.OWNER).status(MemberStatus.ACTIVE).build()));

        service.recordMotion(motionSensor);

        ArgumentCaptor<SecurityEvent> saved = ArgumentCaptor.forClass(SecurityEvent.class);
        verify(securityEventRepository).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo(SecurityEventType.MOTION);
        assertThat(saved.getValue().getLocation()).isEqualTo("Phòng khách");
        assertThat(saved.getValue().getStatus()).isEqualTo(SecurityEventStatus.DETECTED);

        ArgumentCaptor<NotificationEvent> notified = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService).create(notified.capture());
        assertThat(notified.getValue().type()).isEqualTo(NotificationType.SECURITY);
        assertThat(notified.getValue().userId()).isEqualTo(owner.getId());
    }

    @Test
    void recordMotion_withinCooldown_skipsDuplicateEvent() {
        when(securityEventRepository.existsByDeviceIdAndDetectedAtAfter(eq(motionSensor.getId()), any()))
                .thenReturn(true);

        service.recordMotion(motionSensor);

        verify(securityEventRepository, never()).save(any());
        verify(notificationService, never()).create(any());
    }

    @Test
    void list_forRestrictedMember_onlyQueriesVisibleSensors() {
        UUID memberId = UUID.randomUUID();
        Set<UUID> visible = Set.of(UUID.randomUUID());
        Pageable pageable = PageRequest.of(0, 20);
        when(homeAuthorizationService.loadAccessContext(home.getId(), memberId)).thenReturn(new MemberAccessContext(
                home.getId(), memberId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                AccessScope.CUSTOM, null, null));
        when(homeAuthorizationService.getVisibleDeviceIds(home.getId(), memberId, AccessLevel.VIEW)).thenReturn(visible);
        when(securityEventRepository.findByHomeIdAndDeviceIdsWithFilters(home.getId(), visible, null, null, pageable))
                .thenReturn(Page.empty(pageable));

        service.list(memberId, home.getId(), null, null, pageable);

        verify(securityEventRepository, never()).findByHomeIdWithFilters(any(), any(), any(), any());
    }

    @Test
    void list_forRestrictedMemberWithoutVisibleSensors_returnsEmptyPage() {
        UUID memberId = UUID.randomUUID();
        when(homeAuthorizationService.loadAccessContext(home.getId(), memberId)).thenReturn(new MemberAccessContext(
                home.getId(), memberId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                AccessScope.CUSTOM, null, null));
        when(homeAuthorizationService.getVisibleDeviceIds(home.getId(), memberId, AccessLevel.VIEW)).thenReturn(Set.of());

        var result = service.list(memberId, home.getId(), null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
        verify(securityEventRepository, never()).findByHomeIdWithFilters(any(), any(), any(), any());
    }

    @Test
    void updateStatus_fromDetected_marksDismissed() {
        UUID userId = owner.getId();
        SecurityEvent event = SecurityEvent.builder().id(UUID.randomUUID()).home(home)
                .eventType(SecurityEventType.MOTION).build();
        when(securityEventRepository.findByIdAndHomeId(event.getId(), home.getId())).thenReturn(Optional.of(event));
        when(securityEventRepository.save(event)).thenReturn(event);

        var result = service.updateStatus(userId, home.getId(), event.getId(), SecurityEventStatus.DISMISSED);

        assertThat(result.status()).isEqualTo(SecurityEventStatus.DISMISSED);
        verify(homeAuthorizationService).requireOwner(userId, home.getId());
    }

    @Test
    void updateStatus_fromConfirmedToDismissed_isRejected() {
        SecurityEvent event = SecurityEvent.builder().id(UUID.randomUUID()).home(home)
                .eventType(SecurityEventType.MOTION).status(SecurityEventStatus.CONFIRMED).build();
        when(securityEventRepository.findByIdAndHomeId(event.getId(), home.getId())).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.updateStatus(owner.getId(), home.getId(), event.getId(),
                SecurityEventStatus.DISMISSED))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SECURITY_EVENT_INVALID_STATUS);
    }
}
