package com.hesta.backend.event;

import com.hesta.backend.service.MaintenanceEmailNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Gửi email bất đồng bộ sau khi giao dịch ticket đã commit (AFTER_COMMIT + REQUIRES_NEW),
 * đảm bảo lỗi SMTP không bao giờ làm rollback ticket.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MaintenanceEmailListener {

    private final MaintenanceEmailNotificationService emailNotificationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTicketEmailEvent(MaintenanceTicketEmailEvent event) {
        try {
            emailNotificationService.sendForEvent(event);
        } catch (Exception ex) {
            log.warn("Unhandled error while sending maintenance email for ticket {}: {}",
                    event != null ? event.ticketCode() : "null", ex.getMessage());
        }
    }
}
