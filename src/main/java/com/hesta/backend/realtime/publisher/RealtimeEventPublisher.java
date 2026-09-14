package com.hesta.backend.realtime.publisher;

import com.hesta.backend.realtime.model.RealtimeEvent;

public interface RealtimeEventPublisher {
    void publish(RealtimeEvent<?> event);
}
