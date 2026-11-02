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
import com.hesta.backend.dto.request.DeviceUpdateRequest;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.entity.User;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void getDeviceDetailNotFoundThrowsAppException() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDeviceDetail(userId, deviceId))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEVICE_NOT_FOUND);
    }

    @Test
    void getDevicesByRoomNotFoundThrowsAppException() {
        UUID userId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDevicesByRoom(userId, roomId))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ROOM_NOT_FOUND);
    }

    @Test
    void updateDeviceConfigRoomMismatchThrowsAppException() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID otherHomeId = UUID.randomUUID();
        UUID newRoomId = UUID.randomUUID();

        Home home = Home.builder().id(homeId).build();
        Device device = Device.builder().id(deviceId).room(Room.builder().home(home).build())
                .name("Lamp").deviceType("LIGHT").status(DeviceStatus.UNKNOWN)
                .currentState(Map.of()).build();

        Room otherRoom = Room.builder().id(newRoomId).home(Home.builder().id(otherHomeId).build()).build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(roomRepository.findById(newRoomId)).thenReturn(Optional.of(otherRoom));

        DeviceUpdateRequest req = new DeviceUpdateRequest();
        req.setRoomId(newRoomId);

        assertThatThrownBy(() -> service.updateDeviceConfig(userId, deviceId, req))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SCENE_DEVICE_HOME_MISMATCH);
    }

    @Test
    void exportDeviceHistoryCsvSanitizesFormulaInjection() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();

        Home home = Home.builder().id(homeId).build();
        Device device = Device.builder().id(deviceId).room(Room.builder().home(home).build())
                .name("=cmd|' /C calc'!A0").deviceType("LIGHT").status(DeviceStatus.UNKNOWN)
                .currentState(Map.of()).build();

        DeviceStateHistory history = DeviceStateHistory.builder()
                .id(UUID.randomUUID())
                .device(device)
                .changedBy(User.builder().fullName("+dangerousUser").build())
                .build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(deviceStateHistoryRepository.findByDeviceIdOrderByChangedAtDesc(deviceId))
                .thenReturn(List.of(history));

        byte[] csvBytes = service.exportDeviceHistoryCsv(userId, deviceId);
        String csv = new String(csvBytes, StandardCharsets.UTF_8);

        // Formula prefix should be escaped with leading single-quote inside quoted field
        assertThat(csv).contains("\"'=cmd|' /C calc'!A0\"");
        assertThat(csv).contains("\"'+dangerousUser\"");
    }

    @Test
    void getDevicesByHome_activeMember_returnsDeviceList() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();
        Device d1 = Device.builder().id(UUID.randomUUID()).name("Living Room Lamp")
                .deviceType("LIGHT").status(DeviceStatus.ONLINE).room(Room.builder().home(home).build())
                .currentState(Map.of("power", true)).build();
        Device d2 = Device.builder().id(UUID.randomUUID()).name("Door Gate")
                .deviceType("GATE").status(DeviceStatus.ONLINE).room(Room.builder().home(home).build())
                .currentState(Map.of("state", "CLOSED")).build();

        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(d1, d2));

        List<com.hesta.backend.dto.response.DeviceResponse> result = service.getDevicesByHome(userId, homeId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("Living Room Lamp");
        assertThat(result.get(1).getName()).isEqualTo("Door Gate");
        verify(homeAuthorizationService).requireAccess(userId, homeId);
    }

    @Test
    void getDevicesByRoom_activeMember_returnsDeviceList() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();
        Room room = Room.builder().id(roomId).home(home).build();
        Device d = Device.builder().id(UUID.randomUUID()).name("RGB Bulb")
                .deviceType("LIGHT").status(DeviceStatus.ONLINE).room(room)
                .currentState(Map.of("brightness", 80)).build();

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(deviceRepository.findByRoomId(roomId)).thenReturn(List.of(d));

        List<com.hesta.backend.dto.response.DeviceResponse> result = service.getDevicesByRoom(userId, roomId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("RGB Bulb");
        verify(homeAuthorizationService).requireAccess(userId, homeId);
    }

    @Test
    void updateDeviceConfig_validRequest_updatesAndReturnsDevice() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID newRoomId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();
        Room oldRoom = Room.builder().id(UUID.randomUUID()).home(home).build();
        Room newRoom = Room.builder().id(newRoomId).home(home).build();

        Device device = Device.builder().id(deviceId).name("Old Light").icon("bulb-old")
                .deviceType("LIGHT").status(DeviceStatus.ONLINE).room(oldRoom)
                .digitalTwinX(java.math.BigDecimal.valueOf(1.0))
                .digitalTwinY(java.math.BigDecimal.valueOf(2.0))
                .digitalTwinZ(java.math.BigDecimal.valueOf(0.0))
                .settings(new java.util.HashMap<>(Map.of("timer", 10)))
                .build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(roomRepository.findById(newRoomId)).thenReturn(Optional.of(newRoom));
        when(deviceRepository.save(org.mockito.ArgumentMatchers.any(Device.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceUpdateRequest req = new DeviceUpdateRequest();
        req.setName("Updated Smart Light");
        req.setIcon("bulb-new");
        req.setRoomId(newRoomId);
        req.setDigitalTwinX(java.math.BigDecimal.valueOf(5.5));
        req.setDigitalTwinY(java.math.BigDecimal.valueOf(6.5));
        req.setDigitalTwinZ(java.math.BigDecimal.valueOf(1.0));
        req.setSettings(Map.of("autoOff", true));

        var updated = service.updateDeviceConfig(userId, deviceId, req);

        assertThat(updated.getName()).isEqualTo("Updated Smart Light");
        assertThat(updated.getIcon()).isEqualTo("bulb-new");
        assertThat(updated.getDigitalTwinX()).isEqualTo(java.math.BigDecimal.valueOf(5.5));
        assertThat(updated.getDigitalTwinY()).isEqualTo(java.math.BigDecimal.valueOf(6.5));
        verify(homeAuthorizationService).requireSceneManagement(userId, homeId);
    }

    @Test
    void removeDevice_byOwner_setsDeletedTrue() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();
        Device device = Device.builder().id(deviceId).name("Sensor to Delete")
                .deviceType("SENSOR").isDeleted(false).room(Room.builder().home(home).build()).build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        service.removeDevice(userId, deviceId);

        assertThat(device.isDeleted()).isTrue();
        verify(deviceRepository).save(device);
        verify(homeAuthorizationService).requireSceneManagement(userId, homeId);
    }

    @Test
    void removeDevice_notFound_throwsException() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeDevice(userId, deviceId))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEVICE_NOT_FOUND);
    }

    @Test
    void getDeviceHistory_returnsMappedHistoryList() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();
        Device device = Device.builder().id(deviceId).room(Room.builder().home(home).build()).build();

        DeviceStateHistory h1 = DeviceStateHistory.builder().id(UUID.randomUUID()).device(device)
                .previousState(Map.of("power", false)).newState(Map.of("power", true))
                .source(com.hesta.backend.enums.StateChangeSource.MANUAL)
                .changedAt(java.time.OffsetDateTime.now())
                .build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(deviceStateHistoryRepository.findByDeviceIdOrderByChangedAtDesc(deviceId)).thenReturn(List.of(h1));

        var historyList = service.getDeviceHistory(userId, deviceId);

        assertThat(historyList).hasSize(1);
        assertThat(historyList.get(0).getSource()).isEqualTo("MANUAL");
        verify(homeAuthorizationService).requireAccess(userId, homeId);
    }

    @Test
    void exportDeviceHistoryExcel_returnsValidSpreadsheetBytes() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();
        Device device = Device.builder().id(deviceId).name("Living Light")
                .room(Room.builder().name("Living Room").home(home).build()).build();

        DeviceStateHistory h1 = DeviceStateHistory.builder().id(UUID.randomUUID()).device(device)
                .previousState(Map.of("power", false)).newState(Map.of("power", "ON"))
                .source(com.hesta.backend.enums.StateChangeSource.MANUAL)
                .changedAt(java.time.OffsetDateTime.now())
                .build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(deviceStateHistoryRepository.findByDeviceIdOrderByChangedAtDesc(deviceId)).thenReturn(List.of(h1));

        byte[] excelBytes = service.exportDeviceHistoryExcel(userId, deviceId, null, null);

        assertThat(excelBytes).isNotNull();
        assertThat(excelBytes.length).isGreaterThan(100);
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
