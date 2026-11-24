package com.hesta.backend.event;

import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Sends maintenance notifications after the ticket change commits, so a notification
 * failure can never roll back the ticket itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaintenanceNotificationListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMaintenanceNotification(MaintenanceNotificationEvent event) {
        for (UUID recipientId : event.recipientIds()) {
            try {
                notificationService.create(new NotificationEvent(
                        recipientId,
                        event.homeId(),
                        NotificationType.DEVICE,
                        event.title(),
                        event.message(),
                        event.priority()));
            } catch (Exception ex) {
                log.warn("Failed to send maintenance notification to {} in home {}: {}",
                        recipientId, event.homeId(), ex.getMessage());
            }
        }
    }
}
