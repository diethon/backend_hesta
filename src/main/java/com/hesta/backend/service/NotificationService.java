package com.hesta.backend.service;

import com.hesta.backend.dto.response.NotificationPageResponse;
import com.hesta.backend.dto.response.NotificationReadAllResponse;
import com.hesta.backend.dto.response.NotificationResponse;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;

import java.util.UUID;

public interface NotificationService {
    NotificationPageResponse list(UUID userId, UUID homeId, Boolean isRead,
                                  NotificationType type, NotificationPriority priority, int page, int size);

    NotificationResponse get(UUID userId, UUID notificationId);

    NotificationResponse markRead(UUID userId, UUID notificationId);

    NotificationReadAllResponse markAllRead(UUID userId, UUID homeId);
}
