package com.hesta.backend.realtime.transport;

import com.hesta.backend.realtime.model.RealtimeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebSocketRealtimeTransport implements RealtimeTransport {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void publish(RealtimeEvent<?> event) {
        messagingTemplate.convertAndSend(RealtimeDestinations.homeEvents(event.homeId()), event);
    }
}
