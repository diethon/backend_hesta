package com.hesta.backend.realtime.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealtimeEventTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void serializeAndDeserialize_withGenericPayload_preservesContract() throws Exception {
        UUID homeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant timestamp = Instant.parse("2026-09-12T08:00:00Z");
        RealtimeEvent<DeviceStatePayload> event = new RealtimeEvent<>(
                "evt-123",
                RealtimeEventType.DEVICE_STATE_CHANGED,
                homeId,
                deviceId,
                new DeviceStatePayload(true),
                timestamp
        );

        String json = objectMapper.writeValueAsString(event);
        RealtimeEvent<DeviceStatePayload> restored = objectMapper.readValue(
                json,
                new TypeReference<>() {
                }
        );

        assertThat(restored).isEqualTo(event);
        assertThat(json).contains("\"type\":\"DEVICE_STATE_CHANGED\"");
    }

    @Test
    void create_withoutDevice_generatesRequiredMetadata() {
        UUID homeId = UUID.randomUUID();

        RealtimeEvent<DeviceStatePayload> event = RealtimeEvent.create(
                RealtimeEventType.NOTIFICATION_CREATED,
                homeId,
                new DeviceStatePayload(false)
        );

        assertThat(event.eventId()).isNotBlank();
        assertThat(event.homeId()).isEqualTo(homeId);
        assertThat(event.deviceId()).isNull();
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void construct_whenRequiredMetadataMissing_rejectsEvent() {
        UUID homeId = UUID.randomUUID();
        Instant timestamp = Instant.now();
        DeviceStatePayload payload = new DeviceStatePayload(true);

        assertThatThrownBy(() -> new RealtimeEvent<>(
                " ", RealtimeEventType.DEVICE_STATE_CHANGED, homeId, null, payload, timestamp
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeEvent<>(
                "evt-1", null, homeId, null, payload, timestamp
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RealtimeEvent<>(
                "evt-1", RealtimeEventType.DEVICE_STATE_CHANGED, null, null, payload, timestamp
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RealtimeEvent<>(
                "evt-1", RealtimeEventType.DEVICE_STATE_CHANGED, homeId, null, null, timestamp
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RealtimeEvent<>(
                "evt-1", RealtimeEventType.DEVICE_STATE_CHANGED, homeId, null, payload, null
        )).isInstanceOf(NullPointerException.class);
    }

    private record DeviceStatePayload(boolean power) {
    }
}
