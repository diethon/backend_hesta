package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MqttDeviceCommandServiceImplTest {

    @Mock
    private MqttGateway mqttGateway;

    @Mock
    private DeviceRepository deviceRepository;

    private ObjectMapper objectMapper = new ObjectMapper();

    private MqttDeviceCommandServiceImpl commandService;

    private UUID deviceId;
    private Device ledDevice;
    private Device acDevice;

    @BeforeEach
    void setUp() {
        MqttDeviceCommandServiceImpl.pendingCommands.clear();
        java.lang.reflect.Field f = org.springframework.util.ReflectionUtils.findField(MqttDeviceCommandServiceImpl.class, "activeDeviceCommands");
        if (f != null) {
            f.setAccessible(true);
            ((Map<?, ?>) org.springframework.util.ReflectionUtils.getField(f, null)).clear();
        }
        commandService = new MqttDeviceCommandServiceImpl(mqttGateway, deviceRepository, objectMapper);
        ReflectionTestUtils.setField(commandService, "topicPrefix", "hesta/nodes");
        ReflectionTestUtils.setField(commandService, "commandTimeoutMs", 1000L);

        deviceId = UUID.randomUUID();
        EdgeNode node = EdgeNode.builder()
                .id(UUID.fromString("a15a1879-a2e2-4640-800a-6bd30a90892c"))
                .nodeCode("HESTA-APARTMENT-00377b16-F1")
                .build();

        ledDevice = Device.builder()
                .id(deviceId)
                .name("RGB LED")
                .deviceType("LED")
                .localId("pin_4")
                .node(node)
                .currentState(new HashMap<>())
                .build();

        acDevice = Device.builder()
                .id(deviceId)
                .name("Air Conditioner")
                .deviceType("AIR_CONDITIONER")
                .localId("ac_1")
                .node(node)
                .currentState(new HashMap<>())
                .build();
    }

    @Test
    @DisplayName("LED: action POWER_ON should remain POWER_ON in MQTT payload")
    void testSendCommand_Led_PowerOn() throws Exception {
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        commandService.sendCommand(deviceId, "POWER_ON", new HashMap<>(), StateChangeSource.MANUAL);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqttGateway, atLeastOnce()).sendToMqtt(anyString(), anyInt(), payloadCaptor.capture());

        String json = payloadCaptor.getValue();
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        assertEquals("POWER_ON", map.get("action"));
        assertEquals("pin_4", map.get("target"));
        assertEquals("ON", ledDevice.getCurrentState().get("power"));
    }

    @Test
    @DisplayName("LED: action POWER_OFF should remain POWER_OFF in MQTT payload")
    void testSendCommand_Led_PowerOff() throws Exception {
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        commandService.sendCommand(deviceId, "POWER_OFF", new HashMap<>(), StateChangeSource.MANUAL);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqttGateway, atLeastOnce()).sendToMqtt(anyString(), anyInt(), payloadCaptor.capture());

        String json = payloadCaptor.getValue();
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        assertEquals("POWER_OFF", map.get("action"));
        assertEquals("pin_4", map.get("target"));
        assertEquals("OFF", ledDevice.getCurrentState().get("power"));
    }

    @Test
    @DisplayName("LED: action SET_RGB should remain SET_RGB in MQTT payload with root r, g, b and power ON")
    void testSendCommand_Led_SetRgb() throws Exception {
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        Map<String, Object> params = new HashMap<>();
        params.put("r", 255);
        params.put("g", 0);
        params.put("b", 0);
        commandService.sendCommand(deviceId, "SET_RGB", params, StateChangeSource.MANUAL);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqttGateway, atLeastOnce()).sendToMqtt(anyString(), anyInt(), payloadCaptor.capture());

        String json = payloadCaptor.getValue();
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        assertEquals("SET_RGB", map.get("action"));
        assertEquals("pin_4", map.get("target"));
        assertEquals(255, map.get("r"));
        assertEquals(0, map.get("g"));
        assertEquals(0, map.get("b"));

        assertEquals("ON", ledDevice.getCurrentState().get("power"));
        assertNotNull(ledDevice.getCurrentState().get("color"));
        Map<?, ?> colorMap = (Map<?, ?>) ledDevice.getCurrentState().get("color");
        assertEquals(255, colorMap.get("r"));
        assertEquals(0, colorMap.get("g"));
        assertEquals(0, colorMap.get("b"));
    }

    @Test
    @DisplayName("LED: action SET_COLOR should normalize to SET_RGB with nested color map extracted to root")
    void testSendCommand_Led_SetColor_NormalizedToSetRgb() throws Exception {
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        Map<String, Object> params = new HashMap<>();
        Map<String, Object> colorMap = new HashMap<>();
        colorMap.put("r", 0);
        colorMap.put("g", 255);
        colorMap.put("b", 128);
        params.put("color", colorMap);

        commandService.sendCommand(deviceId, "SET_COLOR", params, StateChangeSource.MANUAL);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqttGateway, atLeastOnce()).sendToMqtt(anyString(), anyInt(), payloadCaptor.capture());

        String json = payloadCaptor.getValue();
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        assertEquals("SET_RGB", map.get("action"));
        assertEquals("pin_4", map.get("target"));
        assertEquals(0, map.get("r"));
        assertEquals(255, map.get("g"));
        assertEquals(128, map.get("b"));

        assertEquals("ON", ledDevice.getCurrentState().get("power"));
        Map<?, ?> savedColor = (Map<?, ?>) ledDevice.getCurrentState().get("color");
        assertNotNull(savedColor);
        assertEquals(0, savedColor.get("r"));
        assertEquals(255, savedColor.get("g"));
        assertEquals(128, savedColor.get("b"));
    }

    @Test
    @DisplayName("LED: action SET_RGB clamps RGB values safely to [0, 255]")
    void testSendCommand_Led_SetRgb_ClampsSafely() throws Exception {
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        Map<String, Object> params = new HashMap<>();
        params.put("r", 350);
        params.put("g", -40);
        params.put("b", "100");

        commandService.sendCommand(deviceId, "SET_RGB", params, StateChangeSource.MANUAL);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqttGateway, atLeastOnce()).sendToMqtt(anyString(), anyInt(), payloadCaptor.capture());

        String json = payloadCaptor.getValue();
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        assertEquals("SET_RGB", map.get("action"));
        assertEquals(255, map.get("r"));
        assertEquals(0, map.get("g"));
        assertEquals(100, map.get("b"));

        assertEquals("ON", ledDevice.getCurrentState().get("power"));
        Map<?, ?> savedColor = (Map<?, ?>) ledDevice.getCurrentState().get("color");
        assertEquals(255, savedColor.get("r"));
        assertEquals(0, savedColor.get("g"));
        assertEquals(100, savedColor.get("b"));
    }

    @Test
    @DisplayName("AC: action POWER_ON should normalize to SET_POWER with power=true")
    void testSendCommand_Ac_PowerOn() throws Exception {
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(acDevice));

        commandService.sendCommand(deviceId, "POWER_ON", new HashMap<>(), StateChangeSource.MANUAL);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqttGateway, atLeastOnce()).sendToMqtt(anyString(), anyInt(), payloadCaptor.capture());

        String json = payloadCaptor.getValue();
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        assertEquals("SET_POWER", map.get("action"));
        assertEquals(Boolean.TRUE, map.get("power"));
    }

    @Test
    @DisplayName("Anti-spam: rapidly sending duplicate command within 2s rejects with COMMAND_IN_PROGRESS_DUPLICATE_REJECTED")
    void testAntiSpamDuplicateRejected() {
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        // First command
        commandService.sendCommand(deviceId, "POWER_ON", new HashMap<>(), StateChangeSource.MANUAL);

        // Immediate duplicate command
        com.hesta.backend.dto.command.CommandResult result = commandService
                .sendCommand(deviceId, "POWER_ON", new HashMap<>(), StateChangeSource.MANUAL)
                .join();

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("COMMAND_IN_PROGRESS_DUPLICATE_REJECTED", result.getErrorCode());
        assertEquals("Thiết bị đang thực thi lệnh trước, vui lòng không click liên tục", result.getMessage());
    }

    @Test
    @DisplayName("Circuit Breaker: blocks execution and returns CIRCUIT_BREAKER_OPEN when node breaker is open")
    void testCircuitBreakerOpenBlocked() {
        com.hesta.backend.diagnostics.MqttDiagnosticsService mockDiag = mock(com.hesta.backend.diagnostics.MqttDiagnosticsService.class);
        when(mockDiag.allowExecution("HESTA-APARTMENT-00377b16-F1")).thenReturn(false);
        ReflectionTestUtils.setField(commandService, "diagnosticsService", mockDiag);

        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        com.hesta.backend.dto.command.CommandResult result = commandService
                .sendCommand(deviceId, "POWER_ON", new HashMap<>(), StateChangeSource.MANUAL)
                .join();

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("CIRCUIT_BREAKER_OPEN", result.getErrorCode());
        assertEquals("Mạch điều khiển Node đang ngắt bảo vệ (CIRCUIT OPEN)", result.getMessage());
    }

    @Test
    @DisplayName("Realtime: sendCommand publishes DeviceStateChangedEvent to home")
    void testSendCommand_PublishesDeviceStateChangedEvent() {
        org.springframework.context.ApplicationEventPublisher mockPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(commandService, "applicationEventPublisher", mockPublisher);

        UUID homeId = UUID.randomUUID();
        com.hesta.backend.entity.Home home = com.hesta.backend.entity.Home.builder().id(homeId).build();
        com.hesta.backend.entity.Room room = com.hesta.backend.entity.Room.builder().id(UUID.randomUUID()).home(home).build();
        ledDevice.setRoom(room);

        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        commandService.sendCommand(deviceId, "POWER_ON", new HashMap<>(), StateChangeSource.MANUAL);

        ArgumentCaptor<com.hesta.backend.dto.command.DeviceStateChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(com.hesta.backend.dto.command.DeviceStateChangedEvent.class);
        verify(mockPublisher, times(1)).publishEvent(eventCaptor.capture());

        com.hesta.backend.dto.command.DeviceStateChangedEvent capturedEvent = eventCaptor.getValue();
        assertEquals(homeId, capturedEvent.homeId());
        assertEquals(deviceId, capturedEvent.payload().deviceId());
        assertEquals("ON", capturedEvent.payload().currentState().path("power").asText());
    }

    @Test
    @DisplayName("Realtime: sendCommand triggers Server Optimistic Broadcast via realtimeEventPublisher")
    void testSendCommand_ServerOptimisticBroadcast() {
        com.hesta.backend.realtime.publisher.RealtimeEventPublisher mockRealtime =
                mock(com.hesta.backend.realtime.publisher.RealtimeEventPublisher.class);
        ReflectionTestUtils.setField(commandService, "realtimeEventPublisher", mockRealtime);

        UUID homeId = UUID.randomUUID();
        com.hesta.backend.entity.Home home = com.hesta.backend.entity.Home.builder().id(homeId).build();
        com.hesta.backend.entity.Room room = com.hesta.backend.entity.Room.builder().id(UUID.randomUUID()).home(home).build();
        ledDevice.setRoom(room);

        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        commandService.sendCommand(deviceId, "POWER_ON", new HashMap<>(), StateChangeSource.MANUAL);

        ArgumentCaptor<com.hesta.backend.realtime.model.RealtimeEvent<?>> captor =
                ArgumentCaptor.forClass(com.hesta.backend.realtime.model.RealtimeEvent.class);
        verify(mockRealtime, times(1)).publish(captor.capture());

        com.hesta.backend.realtime.model.RealtimeEvent<?> event = captor.getValue();
        assertEquals(com.hesta.backend.realtime.model.RealtimeEventType.DEVICE_STATE_CHANGED, event.type());
        assertEquals(homeId, event.homeId());
        assertEquals(deviceId, event.deviceId());
    }
}
