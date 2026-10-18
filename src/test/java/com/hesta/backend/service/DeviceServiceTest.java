package com.hesta.backend.service;

import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisher;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.service.impl.DeviceServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {
    @Mock DeviceRepository deviceRepository;
    @Mock HomeAuthorizationService homeAuthorizationService;
    @Mock RoomRepository roomRepository;
    @Mock DeviceStateHistoryRepository deviceStateHistoryRepository;
    @Mock RealtimeEventPublisher realtimeEventPublisher;
    @InjectMocks DeviceServiceImpl service;

    @Test
    void deviceDetailUsesRoomHomeWhenNodeIsAbsent() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();
        Device device = Device.builder().id(deviceId).room(Room.builder().home(home).build())
                .name("Lamp").deviceType("LIGHT").status(DeviceStatus.UNKNOWN)
                .currentState(Map.of()).build();
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        assertThat(service.getDeviceDetail(userId, deviceId).getHomeId()).isEqualTo(homeId);
        verify(homeAuthorizationService).requireAccess(userId, homeId);
    }

    @Test
    void getHomeCapabilitiesReturnsGroupedCapabilitiesAndActionTypes() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID device1Id = UUID.randomUUID();
        UUID device2Id = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();

        Device light = Device.builder().id(device1Id).room(Room.builder().home(home).name("Living").build())
                .name("Main Light").deviceType("LIGHT").status(DeviceStatus.ONLINE)
                .currentState(Map.of("power", "ON")).build();

        Device ac = Device.builder().id(device2Id).room(Room.builder().home(home).name("Living").build())
                .name("Living AC").deviceType("AIR_CONDITIONER").status(DeviceStatus.ONLINE)
                .currentState(Map.of("power", "ON", "temperature", 24)).build();

        when(deviceRepository.findByHomeId(homeId)).thenReturn(java.util.List.of(light, ac));

        com.hesta.backend.dto.response.HomeCapabilitiesResponse response = service.getHomeCapabilities(userId, homeId);

        assertThat(response).isNotNull();
        assertThat(response.getHomeId()).isEqualTo(homeId);
        assertThat(response.getDevices()).hasSize(2);
        assertThat(response.getActionTypes()).contains("TURN_ON", "TURN_OFF", "SET_BRIGHTNESS", "SET_TEMPERATURE");
        assertThat(response.getCapabilities()).anyMatch(c -> c.getCode().equals("POWER"));
        assertThat(response.getCapabilities()).anyMatch(c -> c.getCode().equals("BRIGHTNESS"));
        assertThat(response.getCapabilities()).anyMatch(c -> c.getCode().equals("TEMPERATURE"));
        verify(homeAuthorizationService).requireAccess(userId, homeId);
    }
}
