package com.hesta.backend.service;

import com.hesta.backend.entity.*;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.impl.BehaviorServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BehaviorServiceTest {
    @Mock HomeAuthorizationService authorizationService;
    @Mock DeviceRepository deviceRepository;
    @Mock UserRepository userRepository;
    @Mock BehaviorEventRepository eventRepository;
    @InjectMocks BehaviorServiceImpl service;

    @Test
    void detectsRepeatableMorningPatternWithConfidence() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).name("Home").build();
        Room room = Room.builder().id(UUID.randomUUID()).home(home).name("Bedroom").build();
        Device lamp = Device.builder().id(UUID.randomUUID()).room(room).name("Lamp")
                .deviceType("LIGHT").build();
        OffsetDateTime from = OffsetDateTime.parse("2026-09-01T00:00:00+07:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-09-03T23:59:00+07:00");
        List<BehaviorEvent> events = List.of(
                behavior(home, lamp, "2026-09-01T06:50:00+07:00"),
                behavior(home, lamp, "2026-09-02T07:00:00+07:00"),
                behavior(home, lamp, "2026-09-03T07:10:00+07:00"));
        when(authorizationService.requireAccess(userId, homeId)).thenReturn(home);
        when(eventRepository.findAllByHomeIdAndOccurredAtBetweenOrderByOccurredAtAsc(homeId, from, to)).thenReturn(events);

        var patterns = service.detectPatterns(userId, homeId, from, to);

        assertThat(patterns).hasSize(1);
        assertThat(patterns.getFirst().getPatternType()).isEqualTo("MORNING");
        assertThat(patterns.getFirst().getAverageTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(patterns.getFirst().getConfidence()).isEqualTo(1d);
        assertThat(patterns.getFirst().getRoomName()).isEqualTo("Bedroom");
    }

    @Test
    void predictsNearCurrentTimeOnlyWhenDeviceNeedsChange() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();
        Room room = Room.builder().id(UUID.randomUUID()).home(home).name("Bedroom").build();
        Device lamp = Device.builder().id(UUID.randomUUID()).room(room).name("Lamp")
                .currentState(Map.of("power", "OFF")).build();
        OffsetDateTime from = OffsetDateTime.parse("2026-09-01T00:00:00+07:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-09-03T23:59:00+07:00");
        when(authorizationService.requireAccess(userId, homeId)).thenReturn(home);
        when(deviceRepository.findAllByRoom_Home_IdOrderByNameAsc(homeId)).thenReturn(List.of(lamp));
        when(eventRepository.findAllByHomeIdAndOccurredAtBetweenOrderByOccurredAtAsc(homeId, from, to))
                .thenReturn(List.of(behavior(home, lamp, "2026-09-01T07:00:00+07:00"),
                        behavior(home, lamp, "2026-09-02T07:00:00+07:00"),
                        behavior(home, lamp, "2026-09-03T07:00:00+07:00")));

        var near = service.predict(userId, homeId, from, to, OffsetDateTime.parse("2026-09-04T07:20:00+07:00"));
        var late = service.predict(userId, homeId, from, to, OffsetDateTime.parse("2026-09-04T11:00:00+07:00"));
        lamp.setCurrentState(Map.of("power", "ON"));
        var alreadyOn = service.predict(userId, homeId, from, to, OffsetDateTime.parse("2026-09-04T07:20:00+07:00"));

        assertThat(near).hasSize(1);
        assertThat(near.getFirst().getAction()).isEqualTo("TURN_ON");
        assertThat(late).isEmpty();
        assertThat(alreadyOn).isEmpty();
    }

    private BehaviorEvent behavior(Home home, Device device, String occurredAt) {
        return BehaviorEvent.builder().home(home).device(device).room(device.getRoom()).eventType("DEVICE_ACTION")
                .action("TURN_ON").occurredAt(OffsetDateTime.parse(occurredAt)).build();
    }
}
