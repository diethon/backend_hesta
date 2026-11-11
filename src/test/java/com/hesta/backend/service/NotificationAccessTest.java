package com.hesta.backend.service;

import com.hesta.backend.dto.response.NotificationResponse;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.entity.Notification;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.NotificationRepository;
import com.hesta.backend.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationAccessTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private HomeMemberRepository homeMemberRepository;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private UUID userId;
    private UUID homeId;
    private UUID notificationId;
    private User recipient;
    private Home home;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        homeId = UUID.randomUUID();
        notificationId = UUID.randomUUID();
        recipient = User.builder().id(userId).email("custom@hesta.vn").build();
        home = Home.builder().id(homeId).name("Family Home").build();
    }

    @Test
    void get_whenCustomMemberAccessesDeviceNotification_throws404() {
        Notification notification = Notification.create(
                recipient,
                home,
                NotificationType.DEVICE,
                "Device offline",
                "Living room light is offline",
                NotificationPriority.LOW
        );

        when(notificationRepository.findByIdAndRecipientId(notificationId, userId))
                .thenReturn(Optional.of(notification));

        HomeMember customMember = HomeMember.builder()
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.MEMBER)
                .accessScope(AccessScope.CUSTOM)
                .build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(customMember));

        assertThatThrownBy(() -> notificationService.get(userId, notificationId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    void get_whenCustomMemberAccessesSecurityNotification_allows() {
        Notification notification = Notification.create(
                recipient,
                home,
                NotificationType.SECURITY,
                "Báo động an ninh",
                "Phát hiện xâm nhập",
                NotificationPriority.MEDIUM
        );

        when(notificationRepository.findByIdAndRecipientId(notificationId, userId))
                .thenReturn(Optional.of(notification));

        HomeMember customMember = HomeMember.builder()
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.MEMBER)
                .accessScope(AccessScope.CUSTOM)
                .build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(customMember));

        NotificationResponse response = notificationService.get(userId, notificationId);
        assertThat(response).isNotNull();
        assertThat(response.title()).isEqualTo("Báo động an ninh");
    }

    @Test
    void get_whenCustomMemberAccessesHighPriorityAlert_allows() {
        Notification notification = Notification.create(
                recipient,
                home,
                NotificationType.ANOMALY,
                "Khí gas rò rỉ",
                "Cảnh báo nồng độ khí gas cao",
                NotificationPriority.HIGH
        );

        when(notificationRepository.findByIdAndRecipientId(notificationId, userId))
                .thenReturn(Optional.of(notification));

        HomeMember customMember = HomeMember.builder()
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.MEMBER)
                .accessScope(AccessScope.CUSTOM)
                .build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(customMember));

        NotificationResponse response = notificationService.get(userId, notificationId);
        assertThat(response).isNotNull();
        assertThat(response.priority()).isEqualTo(NotificationPriority.HIGH);
    }

    @Test
    void get_whenOwnerAccessesDeviceNotification_allows() {
        Notification notification = Notification.create(
                recipient,
                home,
                NotificationType.DEVICE,
                "Cập nhật thiết bị",
                "Đèn đã tắt",
                NotificationPriority.LOW
        );

        when(notificationRepository.findByIdAndRecipientId(notificationId, userId))
                .thenReturn(Optional.of(notification));

        HomeMember ownerMember = HomeMember.builder()
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.OWNER)
                .accessScope(AccessScope.ALL)
                .build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(ownerMember));

        NotificationResponse response = notificationService.get(userId, notificationId);
        assertThat(response).isNotNull();
        assertThat(response.title()).isEqualTo("Cập nhật thiết bị");
    }
}
