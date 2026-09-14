package com.hesta.backend.realtime.publisher;

import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.realtime.transport.RealtimeTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RealtimeEventPublisherImplTest {

    @Mock
    private RealtimeTransport realtimeTransport;

    @InjectMocks
    private RealtimeEventPublisherImpl publisher;

    @ParameterizedTest
    @EnumSource(RealtimeEventType.class)
    void publish_forEverySupportedType_usesSharedTransport(RealtimeEventType type) {
        RealtimeEvent<TestPayload> event = RealtimeEvent.create(
                type,
                UUID.randomUUID(),
                new TestPayload("value")
        );

        publisher.publish(event);

        verify(realtimeTransport).publish(event);
    }

    private record TestPayload(String value) {
    }
}
