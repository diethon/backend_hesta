package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.TelemetryPayload;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.SensorReading;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.DeviceType;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisher;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.EdgeNodeRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemetryServiceImplTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private SensorReadingRepository sensorReadingRepository;

    @Mock
    private EdgeNodeRepository edgeNodeRepository;

    @Mock
    private RealtimeEventPublisher realtimeEventPublisher;

    private TelemetryServiceImpl telemetryService;

    @BeforeEach
    void setUp() {
        telemetryService = new TelemetryServiceImpl(
                deviceRepository,
                sensorReadingRepository,
                edgeNodeRepository,
                realtimeEventPublisher
        );
    }

    @Test
    void processTelemetry_Success_ResolvesByNodeAndDeviceName() {
        UUID homeId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        Home home = Home.builder().id(homeId).name("Test Home").build();
        Room room = Room.builder().id(roomId).name("Living Room").home(home).build();
        EdgeNode node = EdgeNode.builder().id(nodeId).nodeCode("ESP32-001").home(home).status(EdgeNodeStatus.CONNECTING).build();
        Device device = Device.builder()
                .id(deviceId)
                .name("DHT-01")
                .deviceType(DeviceType.LIGHT)
                .room(room)
                .node(node)
                .status(DeviceStatus.UNKNOWN)
                .currentState(new HashMap<>())
                .build();

        when(deviceRepository.findByNodeCodeAndName("ESP32-001", "DHT-01"))
                .thenReturn(Optional.of(device));

        TelemetryPayload payload = TelemetryPayload.builder()
                .nodeId("ESP32-001")
                .deviceId("DHT-01")
                .metricType("temperature")
                .value(new BigDecimal("28.5"))
                .unit("C")
                .build();

        telemetryService.processTelemetry("hesta/nodes/ESP32-001/devices/DHT-01/telemetry", payload);

        // Verify SensorReading was saved
        ArgumentCaptor<SensorReading> readingCaptor = ArgumentCaptor.forClass(SensorReading.class);
        verify(sensorReadingRepository, times(1)).save(readingCaptor.capture());
        SensorReading savedReading = readingCaptor.getValue();
        assertEquals("temperature", savedReading.getMetricType());
        assertEquals(new BigDecimal("28.5"), savedReading.getValue());
        assertEquals("C", savedReading.getUnit());
        assertEquals(device, savedReading.getDevice());

        // Verify Device state updated
        verify(deviceRepository, times(1)).save(device);
        assertEquals(DeviceStatus.ONLINE, device.getStatus());
        assertEquals(new BigDecimal("28.5"), device.getCurrentState().get("temperature"));

        // Verify Realtime event published
        verify(realtimeEventPublisher, times(1)).publish(any());
    }

    @Test
    void processTelemetry_DeviceNotFound_DoesNotThrow() {
        when(deviceRepository.findByNodeCodeAndName("ESP32-001", "UNKNOWN-01"))
                .thenReturn(Optional.empty());

        TelemetryPayload payload = TelemetryPayload.builder()
                .nodeId("ESP32-001")
                .deviceId("UNKNOWN-01")
                .metricType("motion")
                .value(BigDecimal.ONE)
                .unit("boolean")
                .build();

        assertDoesNotThrow(() ->
                telemetryService.processTelemetry("hesta/nodes/ESP32-001/devices/UNKNOWN-01/telemetry", payload)
        );

        verify(sensorReadingRepository, never()).save(any());
        verify(deviceRepository, never()).save(any());
        verify(realtimeEventPublisher, never()).publish(any());
    }

    @Test
    void processTelemetry_NullPayload_DoesNotThrow() {
        assertDoesNotThrow(() ->
                telemetryService.processTelemetry("test/topic", null)
        );
        verifyNoInteractions(sensorReadingRepository, deviceRepository, realtimeEventPublisher);
    }
}
