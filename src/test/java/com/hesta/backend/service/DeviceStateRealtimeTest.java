package com.hesta.backend.service;

import com.hesta.backend.support.TwinHealthTestSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.impl.DeviceServiceImpl;
import com.hesta.backend.support.TwinFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceStateRealtimeTest {
    @Mock DeviceRepository devices;
    @Mock HomeRepository homes;
    @Mock HomeMemberRepository members;
    @Mock RoomRepository rooms;
    @Mock DeviceStateHistoryRepository history;
    @Mock ApplicationEventPublisher events;
    private final TwinFixtures fixture = new TwinFixtures();
    private DeviceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DeviceServiceImpl(devices, new HomeAuthorizationService(homes, members), rooms, history, events,
                TwinHealthTestSupport.mapper(new ObjectMapper()));
    }

    @Test
    void stateUpdate_afterSaving_queuesDetachedSingleNodePayload() {
        fixture.light.setCapabilities(List.of("power", "brightness"));
        when(devices.findById(fixture.light.getId())).thenReturn(Optional.of(fixture.light));
        service.updateDeviceStateFromMqtt(fixture.light.getId().toString(),
                Map.of("power", "OFF", "brightness", 0, "unsupported", 42));

        var captor = ArgumentCaptor.forClass(DeviceStateChangedEvent.class);
        var order = inOrder(devices, events);
        order.verify(devices).findById(fixture.light.getId());
        order.verify(devices).save(fixture.light);
        order.verify(events).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.homeId()).isEqualTo(fixture.home.getId());
        assertThat(event.payload().deviceId()).isEqualTo(fixture.light.getId());
        assertThat(event.payload().roomId()).isEqualTo(fixture.living.getId());
        assertThat(event.payload().currentState().path("power").asText()).isEqualTo("OFF");
        assertThat(event.payload().currentState().has("unsupported")).isFalse();
        fixture.light.getCurrentState().put("power", "ON");
        assertThat(event.payload().currentState().path("power").asText()).isEqualTo("OFF");
        verifyNoMoreInteractions(events);
    }

    @Test
    void stateUpdate_invalidDeviceId_doesNotQueueEvent() {
        service.updateDeviceStateFromMqtt("invalid", Map.of());
        verifyNoInteractions(devices, events);
    }
}
