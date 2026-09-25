package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.NotificationPageResponse;
import com.hesta.backend.dto.response.NotificationReadAllResponse;
import com.hesta.backend.dto.response.NotificationResponse;
import com.hesta.backend.entity.Notification;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.NotificationRepository;
import com.hesta.backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private final NotificationRepository notifications;

    @Override
    @Transactional(readOnly = true)
    public NotificationPageResponse list(UUID userId, UUID homeId, Boolean isRead,
                                         NotificationType type, NotificationPriority priority, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Specification<Notification> scope = (root, query, builder) -> builder.equal(root.get("recipient").get("id"), userId);
        if (homeId != null) {
            scope = scope.and((root, query, builder) -> builder.equal(root.get("home").get("id"), homeId));
        }
        if (isRead != null) {
            scope = scope.and((root, query, builder) -> builder.equal(root.get("read"), isRead));
        }
        if (type != null) {
            scope = scope.and((root, query, builder) -> builder.equal(root.get("type"), type));
        }
        if (priority != null) {
            scope = scope.and((root, query, builder) -> builder.equal(root.get("priority"), priority));
        }
        var result = notifications.findAll(scope, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return NotificationPageResponse.from(result.map(NotificationResponse::from));
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationResponse get(UUID userId, UUID notificationId) {
        return NotificationResponse.from(findOwned(userId, notificationId));
    }

    @Override
    @Transactional
    public NotificationResponse markRead(UUID userId, UUID notificationId) {
        Notification notification = findOwned(userId, notificationId);
        notification.setRead(true);
        return NotificationResponse.from(notification);
    }

    @Override
    @Transactional
    public NotificationReadAllResponse markAllRead(UUID userId, UUID homeId) {
        int count = homeId == null ? notifications.markAllReadForUser(userId)
                : notifications.markAllReadForHome(userId, homeId);
        return new NotificationReadAllResponse(count);
    }

    private Notification findOwned(UUID userId, UUID notificationId) {
        return notifications.findByIdAndRecipientId(notificationId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.NOTIFICATION_NOT_FOUND));
    }
}
