package com.hesta.backend.realtime.publisher;

import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.transport.RealtimeTransport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RealtimeEventPublisherImpl implements RealtimeEventPublisher {

    private final RealtimeTransport realtimeTransport;

    @Override
    public void publish(RealtimeEvent<?> event) {
        realtimeTransport.publish(event);
    }
}
