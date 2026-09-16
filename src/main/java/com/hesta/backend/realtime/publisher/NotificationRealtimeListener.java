package com.hesta.backend.realtime.publisher;

import com.hesta.backend.dto.command.NotificationCreatedEvent;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class NotificationRealtimeListener {

    private final RealtimeEventPublisher realtimeEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        realtimeEventPublisher.publish(RealtimeEvent.create(
                RealtimeEventType.NOTIFICATION_CREATED,
                event.payload().homeId(),
                event.payload()
        ));
    }
}
