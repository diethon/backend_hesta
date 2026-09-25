package com.hesta.backend.service;

import com.hesta.backend.dto.command.NotificationCreatedEvent;
import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.dto.response.NotificationResponse;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.entity.Notification;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.repository.NotificationRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private HomeRepository homeRepository;
    @Mock
    private HomeMemberRepository homeMemberRepository;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private UUID userId;
    private UUID homeId;
    private User user;
    private Home home;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        homeId = UUID.randomUUID();
        user = User.builder().id(userId).fullName("Recipient").email("recipient@example.com").build();
        home = Home.builder().id(homeId).name("Home").createdBy(user).build();
    }

    @Test
    void create_withValidEvent_persistsUnreadNotificationAndRaisesCreatedEvent() {
        NotificationEvent event = validEvent();
        arrangeCreation();

        NotificationResponse result = notificationService.create(event);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isRead()).isFalse();
        assertThat(saved.getValue().getRecipient().getId()).isEqualTo(userId);
        assertThat(saved.getValue().getHome().getId()).isEqualTo(homeId);
        assertThat(result.id()).isNotNull();
        assertThat(result.isRead()).isFalse();

        ArgumentCaptor<NotificationCreatedEvent> raised = ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        verify(applicationEventPublisher).publishEvent(raised.capture());
        assertThat(raised.getValue().payload().notificationId()).isEqualTo(result.id());
        assertThat(raised.getValue().payload().recipientId()).isEqualTo(userId);
        assertThat(raised.getValue().payload().homeId()).isEqualTo(homeId);
    }

    @Test
    void receiveEvent_withValidEvent_usesSharedCreationFlow() {
        arrangeCreation();

        NotificationResponse result = notificationService.receiveEvent(validEvent());

        assertThat(result.type()).isEqualTo(NotificationType.SECURITY);
        verify(notificationRepository).saveAndFlush(any(Notification.class));
        verify(applicationEventPublisher).publishEvent(any(NotificationCreatedEvent.class));
    }

    @Test
    void create_withInvalidEvent_rejectsBeforePersistence() {
        NotificationEvent invalid = new NotificationEvent(
                userId, homeId, NotificationType.SYSTEM, " ", "message", NotificationPriority.LOW
        );

        assertThatThrownBy(() -> notificationService.create(invalid))
                .isInstanceOf(AppException.class)
                .extracting(exception -> ((AppException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_EVENT_INVALID);
        verify(notificationRepository, never()).saveAndFlush(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void create_whenRecipientIsNotActiveHomeMember_rejectsCreation() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(HomeMember.builder().home(home).user(user).status(MemberStatus.DISABLED).build()));

        assertThatThrownBy(() -> notificationService.create(validEvent()))
                .isInstanceOf(AppException.class)
                .extracting(exception -> ((AppException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_whenPersistenceFails_doesNotRaiseRealtimeTriggerEvent() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(activeMembership()));
        when(notificationRepository.saveAndFlush(any(Notification.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> notificationService.create(validEvent()))
                .isInstanceOf(IllegalStateException.class);
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void list_withFilters_returnsPersistedNotificationsAndClampsPageSize() {
        Notification notification = persistedNotification(false);
        when(notificationRepository.findAccessible(
                eq(userId), eq(homeId), eq(false), eq(NotificationType.SECURITY),
                eq(NotificationPriority.HIGH), any(Pageable.class)
        )).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(5);
            return new PageImpl<>(List.of(notification), pageable, 1);
        });

        var result = notificationService.list(
                userId, homeId, false, NotificationType.SECURITY, NotificationPriority.HIGH, -2, 500
        );

        assertThat(result.content()).singleElement().extracting(NotificationResponse::id)
                .isEqualTo(notification.getId());
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(100);
    }

    @Test
    void get_whenNotificationBelongsToCurrentUser_returnsNotification() {
        Notification notification = persistedNotification(false);
        when(notificationRepository.findByIdAndRecipientId(notification.getId(), userId))
                .thenReturn(Optional.of(notification));

        NotificationResponse result = notificationService.get(userId, notification.getId());

        assertThat(result.id()).isEqualTo(notification.getId());
    }

    @Test
    void get_whenNotificationIsNotOwned_rejectsCrossUserAccess() {
        UUID notificationId = UUID.randomUUID();
        when(notificationRepository.findByIdAndRecipientId(notificationId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.get(userId, notificationId))
                .isInstanceOf(AppException.class)
                .extracting(exception -> ((AppException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    void markAsRead_whenUnread_changesStateToRead() {
        Notification notification = persistedNotification(false);
        when(notificationRepository.findByIdAndRecipientId(notification.getId(), userId))
                .thenReturn(Optional.of(notification));

        NotificationResponse result = notificationService.markAsRead(userId, notification.getId());

        assertThat(result.isRead()).isTrue();
        assertThat(notification.isRead()).isTrue();
    }

    @Test
    void markAsRead_whenAlreadyRead_remainsRead() {
        Notification notification = persistedNotification(true);
        when(notificationRepository.findByIdAndRecipientId(notification.getId(), userId))
                .thenReturn(Optional.of(notification));

        NotificationResponse result = notificationService.markAsRead(userId, notification.getId());

        assertThat(result.isRead()).isTrue();
    }

    @Test
    void markAllAsRead_scopesUpdateToCurrentUserAndRequestedHome() {
        when(notificationRepository.markAllAsRead(userId, homeId)).thenReturn(3);

        var result = notificationService.markAllAsRead(userId, homeId);

        assertThat(result.updatedCount()).isEqualTo(3);
        verify(notificationRepository).markAllAsRead(userId, homeId);
    }

    private void arrangeCreation() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(activeMembership()));
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
            Notification candidate = invocation.getArgument(0);
            return Notification.builder()
                    .id(UUID.randomUUID())
                    .recipient(candidate.getRecipient())
                    .home(candidate.getHome())
                    .type(candidate.getType())
                    .title(candidate.getTitle())
                    .message(candidate.getMessage())
                    .priority(candidate.getPriority())
                    .read(candidate.isRead())
                    .createdAt(OffsetDateTime.now())
                    .build();
        });
    }

    private HomeMember activeMembership() {
        return HomeMember.builder().home(home).user(user).status(MemberStatus.ACTIVE).build();
    }

    private NotificationEvent validEvent() {
        return new NotificationEvent(
                userId,
                homeId,
                NotificationType.SECURITY,
                "Security alert",
                "Motion detected",
                NotificationPriority.HIGH
        );
    }

    private Notification persistedNotification(boolean read) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .recipient(user)
                .home(home)
                .type(NotificationType.SECURITY)
                .title("Security alert")
                .message("Motion detected")
                .priority(NotificationPriority.HIGH)
                .read(read)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
