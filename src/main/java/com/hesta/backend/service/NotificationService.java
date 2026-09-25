package com.hesta.backend.service;

import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.dto.response.NotificationReadAllResponse;
import com.hesta.backend.dto.response.NotificationResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;

import java.util.UUID;

public interface NotificationService {

    NotificationResponse create(NotificationEvent event);

    NotificationResponse receiveEvent(NotificationEvent event);

    PageResponse<NotificationResponse> list(
            UUID currentUserId,
            UUID homeId,
            Boolean isRead,
            NotificationType type,
            NotificationPriority priority,
            int page,
            int size
    );

    NotificationResponse get(UUID currentUserId, UUID notificationId);

    NotificationResponse markAsRead(UUID currentUserId, UUID notificationId);

    NotificationReadAllResponse markAllAsRead(UUID currentUserId, UUID homeId);
}
