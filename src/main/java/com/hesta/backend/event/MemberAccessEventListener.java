package com.hesta.backend.event;

import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisher;
import com.hesta.backend.realtime.transport.WebSocketSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class MemberAccessEventListener {

    private final WebSocketSessionManager webSocketSessionManager;
    private final RealtimeEventPublisher realtimeEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberAccessChanged(MemberAccessChangedEvent event) {
        log.info("MemberAccessChanged event published after commit: homeId={}, userId={}", event.homeId(), event.userId());
        try {
            realtimeEventPublisher.publish(RealtimeEvent.create(
                    RealtimeEventType.MEMBER_ACCESS_UPDATED,
                    event.homeId(),
                    event
            ));
        } catch (Exception e) {
            log.warn("Failed to publish MEMBER_ACCESS_UPDATED realtime event: {}", e.getMessage());
        }

        webSocketSessionManager.closeUserSessions(event.userId());
    }
}
