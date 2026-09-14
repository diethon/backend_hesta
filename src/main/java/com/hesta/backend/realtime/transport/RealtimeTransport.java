package com.hesta.backend.realtime.transport;

import com.hesta.backend.realtime.model.RealtimeEvent;

public interface RealtimeTransport {
    void publish(RealtimeEvent<?> event);
}
