package com.hesta.backend.realtime.publisher;

import com.hesta.backend.dto.command.NotificationCreatedEvent;
import com.hesta.backend.dto.response.NotificationRealtimePayload;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationRealtimeListenerTest {

    @Mock
    private RealtimeEventPublisher realtimeEventPublisher;

    @InjectMocks
    private NotificationRealtimeListener listener;

    @Test
    void onNotificationCreated_publishesExpectedHomeRoutedPayload() {
        UUID notificationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        NotificationRealtimePayload payload = new NotificationRealtimePayload(
                notificationId, recipientId, homeId, false, OffsetDateTime.now()
        );

        listener.onNotificationCreated(new NotificationCreatedEvent(payload));

        ArgumentCaptor<RealtimeEvent<?>> eventCaptor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(realtimeEventPublisher).publish(eventCaptor.capture());
        RealtimeEvent<?> realtimeEvent = eventCaptor.getValue();
        assertThat(realtimeEvent.type()).isEqualTo(RealtimeEventType.NOTIFICATION_CREATED);
        assertThat(realtimeEvent.homeId()).isEqualTo(homeId);
        assertThat(realtimeEvent.deviceId()).isNull();
        assertThat(realtimeEvent.data()).isEqualTo(payload);
    }

    @Test
    void onNotificationCreated_isBoundExclusivelyToAfterCommit() throws NoSuchMethodException {
        Method method = NotificationRealtimeListener.class
                .getMethod("onNotificationCreated", NotificationCreatedEvent.class);
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isFalse();
    }
}
