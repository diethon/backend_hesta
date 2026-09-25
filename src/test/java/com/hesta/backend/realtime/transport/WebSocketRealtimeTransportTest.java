package com.hesta.backend.realtime.transport;

import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebSocketRealtimeTransportTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private WebSocketRealtimeTransport transport;

    @Test
    void publish_withHomeEvent_routesOnlyToThatHomeDestination() {
        UUID firstHomeId = UUID.randomUUID();
        UUID secondHomeId = UUID.randomUUID();
        RealtimeEvent<TestPayload> event = RealtimeEvent.create(
                RealtimeEventType.SENSOR_READING_UPDATED,
                firstHomeId,
                new TestPayload("23.5")
        );

        transport.publish(event);

        verify(messagingTemplate).convertAndSend(RealtimeDestinations.homeEvents(firstHomeId), event);
        verify(messagingTemplate, never()).convertAndSend(RealtimeDestinations.homeEvents(secondHomeId), event);
    }

    private record TestPayload(String value) {
    }
}
