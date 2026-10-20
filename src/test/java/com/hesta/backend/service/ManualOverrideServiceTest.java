package com.hesta.backend.service;

import com.hesta.backend.entity.BehaviorEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.User;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.repository.BehaviorEventRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.impl.ManualOverrideServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualOverrideServiceTest {

    @Mock
    private BehaviorEventRepository events;

    @Mock
    private UserRepository users;

    @Mock
    private DeviceRepository devices;

    @InjectMocks
    private ManualOverrideServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final UUID homeId = UUID.randomUUID();

    @Test
    void activate_savesEventAndReturnsExpiry() {
        Home home = Home.builder().id(homeId).build();
        Room room = Room.builder().home(home).build();
        Device device = Device.builder().id(deviceId).room(room).build();
        User user = User.builder().id(userId).build();

        when(users.findById(userId)).thenReturn(Optional.of(user));

        OffsetDateTime expiry = service.activate(device, userId, "MANUAL_CLICK");

        assertThat(expiry).isAfter(OffsetDateTime.now().plusMinutes(29));
        ArgumentCaptor<BehaviorEvent> captor = ArgumentCaptor.forClass(BehaviorEvent.class);
        verify(events).save(captor.capture());
        BehaviorEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("MANUAL_OVERRIDE");
        assertThat(saved.getAction()).isEqualTo("MANUAL_CLICK");
    }

    @Test
    void recordOverride_withCustomMinutes_savesEvent() {
        Home home = Home.builder().id(homeId).build();
        Device device = Device.builder().id(deviceId).room(Room.builder().home(home).build()).build();
        User user = User.builder().id(userId).build();

        when(devices.findById(deviceId)).thenReturn(Optional.of(device));
        when(users.findById(userId)).thenReturn(Optional.of(user));

        OffsetDateTime expiry = service.recordOverride(deviceId, userId, 45);

        assertThat(expiry).isAfter(OffsetDateTime.now().plusMinutes(44));
        verify(events).save(any(BehaviorEvent.class));
    }

    @Test
    void extendOverride_extendsExpiryFromCurrent() {
        Home home = Home.builder().id(homeId).build();
        Device device = Device.builder().id(deviceId).room(Room.builder().home(home).build()).build();
        User user = User.builder().id(userId).build();

        OffsetDateTime future = OffsetDateTime.now().plusMinutes(20);
        BehaviorEvent currentEvent = BehaviorEvent.builder()
                .action("OVERRIDE")
                .currentState(Map.of("expiresAt", future.toString()))
                .occurredAt(OffsetDateTime.now())
                .build();

        when(devices.findById(deviceId)).thenReturn(Optional.of(device));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(events.findTopByDeviceIdAndEventTypeOrderByOccurredAtDesc(deviceId, "MANUAL_OVERRIDE"))
                .thenReturn(Optional.of(currentEvent));

        OffsetDateTime extended = service.extendOverride(deviceId, userId, 60);

        assertThat(extended).isAfter(future.plusMinutes(59));
        verify(events).save(any(BehaviorEvent.class));
    }

    @Test
    void resumeAutomation_savesResumeEvent() {
        Home home = Home.builder().id(homeId).build();
        Device device = Device.builder().id(deviceId).room(Room.builder().home(home).build()).build();
        User user = User.builder().id(userId).build();

        when(users.findById(userId)).thenReturn(Optional.of(user));

        service.resumeAutomation(device, userId);

        ArgumentCaptor<BehaviorEvent> captor = ArgumentCaptor.forClass(BehaviorEvent.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("RESUME");
    }

    @Test
    void isActive_returnsTrueWhenNotExpired() {
        OffsetDateTime future = OffsetDateTime.now().plusMinutes(15);
        BehaviorEvent event = BehaviorEvent.builder()
                .action("OVERRIDE")
                .currentState(Map.of("expiresAt", future.toString()))
                .occurredAt(OffsetDateTime.now())
                .build();

        when(events.findTopByDeviceIdAndEventTypeOrderByOccurredAtDesc(deviceId, "MANUAL_OVERRIDE"))
                .thenReturn(Optional.of(event));

        assertThat(service.isActive(deviceId)).isTrue();
    }

    @Test
    void isActive_returnsFalseWhenExpiredOrResumed() {
        OffsetDateTime past = OffsetDateTime.now().minusMinutes(10);
        BehaviorEvent expiredEvent = BehaviorEvent.builder()
                .action("OVERRIDE")
                .currentState(Map.of("expiresAt", past.toString()))
                .occurredAt(past.minusMinutes(30))
                .build();

        when(events.findTopByDeviceIdAndEventTypeOrderByOccurredAtDesc(deviceId, "MANUAL_OVERRIDE"))
                .thenReturn(Optional.of(expiredEvent));

        assertThat(service.isActive(deviceId)).isFalse();

        BehaviorEvent resumeEvent = BehaviorEvent.builder()
                .action("RESUME")
                .occurredAt(OffsetDateTime.now())
                .build();
        when(events.findTopByDeviceIdAndEventTypeOrderByOccurredAtDesc(deviceId, "MANUAL_OVERRIDE"))
                .thenReturn(Optional.of(resumeEvent));

        assertThat(service.isActive(deviceId)).isFalse();
    }
}
