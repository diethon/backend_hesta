package com.hesta.backend.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class MemberAccessEventListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMemberAccessChanged(MemberAccessChangedEvent event) {
        log.info("MemberAccessChanged event published after commit: homeId={}, userId={}", event.homeId(), event.userId());
    }
}
