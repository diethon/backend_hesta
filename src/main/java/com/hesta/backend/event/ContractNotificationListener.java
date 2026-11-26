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

@Slf4j
@Component
@RequiredArgsConstructor
public class ContractNotificationListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onContractNotification(ContractNotificationEvent event) {
        if (event.recipientIds() == null || event.recipientIds().isEmpty()) {
            return;
        }
        for (UUID recipientId : event.recipientIds()) {
            try {
                notificationService.create(new NotificationEvent(
                        recipientId,
                        event.homeId(),
                        NotificationType.SYSTEM,
                        event.title(),
                        event.message(),
                        event.priority()
                ));
            } catch (Exception ex) {
                log.warn("Failed to send contract notification to {}: {}", recipientId, ex.getMessage());
            }
        }
    }
}
