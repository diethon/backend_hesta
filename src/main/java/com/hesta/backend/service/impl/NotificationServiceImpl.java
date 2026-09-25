package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.NotificationCreatedEvent;
import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.dto.response.NotificationReadAllResponse;
import com.hesta.backend.dto.response.NotificationRealtimePayload;
import com.hesta.backend.dto.response.NotificationResponse;
import com.hesta.backend.dto.response.PageResponse;
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
import com.hesta.backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationServiceImpl implements NotificationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final HomeRepository homeRepository;
    private final HomeMemberRepository homeMemberRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    @Transactional
    public NotificationResponse create(NotificationEvent event) {
        return createNotification(event);
    }

    @Override
    @Transactional
    public NotificationResponse receiveEvent(NotificationEvent event) {
        return createNotification(event);
    }

    @Override
    public PageResponse<NotificationResponse> list(
            UUID currentUserId,
            UUID homeId,
            Boolean isRead,
            NotificationType type,
            NotificationPriority priority,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageRequest = PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );
        Page<NotificationResponse> notifications = notificationRepository
                .findAccessible(currentUserId, homeId, isRead, type, priority, pageRequest)
                .map(NotificationResponse::fromEntity);
        return PageResponse.from(notifications);
    }

    @Override
    public NotificationResponse get(UUID currentUserId, UUID notificationId) {
        return NotificationResponse.fromEntity(findOwnedNotification(currentUserId, notificationId));
    }

    @Override
    @Transactional
    public NotificationResponse markAsRead(UUID currentUserId, UUID notificationId) {
        Notification notification = findOwnedNotification(currentUserId, notificationId);
        notification.markAsRead();
        return NotificationResponse.fromEntity(notification);
    }

    @Override
    @Transactional
    public NotificationReadAllResponse markAllAsRead(UUID currentUserId, UUID homeId) {
        return new NotificationReadAllResponse(notificationRepository.markAllAsRead(currentUserId, homeId));
    }

    private NotificationResponse createNotification(NotificationEvent event) {
        validate(event);

        User recipient = userRepository.findById(event.userId())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        Home home = homeRepository.findById(event.homeId())
                .orElseThrow(() -> new AppException(ErrorCode.NOTIFICATION_EVENT_INVALID));
        HomeMember membership = homeMemberRepository.findByHomeIdAndUserId(event.homeId(), event.userId())
                .filter(member -> member.getStatus() == MemberStatus.ACTIVE)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));

        Notification notification = Notification.create(
                recipient,
                membership.getHome() == null ? home : membership.getHome(),
                event.type(),
                event.title().trim(),
                event.message().trim(),
                event.priority()
        );
        NotificationResponse response = NotificationResponse.fromEntity(notificationRepository.saveAndFlush(notification));
        applicationEventPublisher.publishEvent(
                new NotificationCreatedEvent(NotificationRealtimePayload.from(response))
        );
        return response;
    }

    private Notification findOwnedNotification(UUID currentUserId, UUID notificationId) {
        return notificationRepository.findByIdAndRecipientId(notificationId, currentUserId)
                .orElseThrow(() -> new AppException(ErrorCode.NOTIFICATION_NOT_FOUND));
    }

    private void validate(NotificationEvent event) {
        if (event == null
                || event.userId() == null
                || event.homeId() == null
                || event.type() == null
                || event.priority() == null
                || event.title() == null
                || event.title().isBlank()
                || event.title().trim().length() > 150
                || event.message() == null
                || event.message().isBlank()) {
            throw new AppException(ErrorCode.NOTIFICATION_EVENT_INVALID);
        }
    }
}
