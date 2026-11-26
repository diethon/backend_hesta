package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.EdgeNodeStatus;
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
        MqttDeviceCommandServiceImpl.commandDeviceMap.clear();
        MqttDeviceCommandServiceImpl.apertureTransitionStartTimes.clear();
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
    @DisplayName("Fast ACK path: SUCCESS ack resolves the command; FAILED ack is left to the receiver")
    void testResolveFromInbound_Ack() throws Exception {
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));
        ReflectionTestUtils.setField(commandService, "commandTimeoutMs", 3000L);

        var future = commandService.sendCommand(deviceId, "TURN_ON", new HashMap<>(), StateChangeSource.SCENE);
        String commandId = MqttDeviceCommandServiceImpl.commandDeviceMap.entrySet().stream()
                .filter(e -> e.getValue().equals(deviceId)).findFirst().orElseThrow().getKey();

        MqttDeviceCommandServiceImpl.resolveFromInbound("hesta/nodes/n1/devices/" + deviceId + "/ack",
                "{\"commandId\":\"" + commandId + "\",\"status\":\"FAILED\",\"errorCode\":\"E1\"}");
        Thread.sleep(100);
        assertFalse(future.isDone(), "failed ack must not be counted as success");

        long t0 = System.currentTimeMillis();
        MqttDeviceCommandServiceImpl.resolveFromInbound("hesta/nodes/n1/devices/" + deviceId + "/ack",
                "{\"commandId\":\"" + commandId + "\",\"status\":\"SUCCESS\",\"state\":{\"power\":true}}");
        CommandResult result = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(result.isSuccess());
        assertEquals(commandId, result.getCommandId(), "result must carry the commandId that was published");
        assertTrue(System.currentTimeMillis() - t0 < 1000, "ack must resolve immediately, not at timeout");
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
        com.hesta.backend.realtime.publisher.RealtimeEventPublisher realtime =
                mock(com.hesta.backend.realtime.publisher.RealtimeEventPublisher.class);
        ReflectionTestUtils.setField(commandService, "realtimeEventPublisher", realtime);

        // Production runs inside sendCommand's @Transactional: the AFTER_COMMIT listener does the broadcast.
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            commandService.sendCommand(deviceId, "POWER_ON", new HashMap<>(), StateChangeSource.MANUAL);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        ArgumentCaptor<com.hesta.backend.dto.command.DeviceStateChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(com.hesta.backend.dto.command.DeviceStateChangedEvent.class);
        verify(mockPublisher, times(1)).publishEvent(eventCaptor.capture());
        verifyNoInteractions(realtime); // no second, pre-commit copy of the same change

        com.hesta.backend.dto.command.DeviceStateChangedEvent capturedEvent = eventCaptor.getValue();
        assertEquals(homeId, capturedEvent.homeId());
        assertEquals(deviceId, capturedEvent.payload().deviceId());
        assertEquals("ON", capturedEvent.payload().currentState().path("power").asText());
    }

    @Test
    @DisplayName("Realtime: without a transaction (chained scene command) the change is broadcast directly, once")
    void testSendCommand_NoTransactionBroadcastsDirectlyOnce() {
        org.springframework.context.ApplicationEventPublisher mockPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        com.hesta.backend.realtime.publisher.RealtimeEventPublisher realtime =
                mock(com.hesta.backend.realtime.publisher.RealtimeEventPublisher.class);
        ReflectionTestUtils.setField(commandService, "applicationEventPublisher", mockPublisher);
        ReflectionTestUtils.setField(commandService, "realtimeEventPublisher", realtime);
        ledDevice.setRoom(com.hesta.backend.entity.Room.builder()
                .home(com.hesta.backend.entity.Home.builder().id(UUID.randomUUID()).build()).build());
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        commandService.sendCommand(deviceId, "POWER_ON", new HashMap<>(), StateChangeSource.SCENE);

        verify(realtime, times(1)).publish(any());
        verifyNoInteractions(mockPublisher);
    }

    @Test
    @DisplayName("Timeout: the assumed state is reverted and broadcast so other screens show the real state")
    void testSendCommand_TimeoutRevertsOptimisticState() {
        com.hesta.backend.realtime.publisher.RealtimeEventPublisher realtime =
                mock(com.hesta.backend.realtime.publisher.RealtimeEventPublisher.class);
        ReflectionTestUtils.setField(commandService, "realtimeEventPublisher", realtime);
        ReflectionTestUtils.setField(commandService, "transactionManager",
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        ReflectionTestUtils.setField(commandService, "commandTimeoutMs", 50L);
        ledDevice.setRoom(com.hesta.backend.entity.Room.builder()
                .home(com.hesta.backend.entity.Home.builder().id(UUID.randomUUID()).build()).build());
        ledDevice.setCurrentState(new HashMap<>(Map.of("power", "OFF")));
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        com.hesta.backend.dto.command.CommandResult result = commandService
                .sendCommand(deviceId, "TURN_ON", new HashMap<>(), StateChangeSource.MANUAL).join();

        assertEquals("TIMEOUT", result.getStatus());
        assertEquals("OFF", ledDevice.getCurrentState().get("power"));
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<com.hesta.backend.realtime.model.RealtimeEvent> sent =
                ArgumentCaptor.forClass(com.hesta.backend.realtime.model.RealtimeEvent.class);
        verify(realtime, times(2)).publish(sent.capture()); // optimistic ON, then the correction
        var correction = (com.hesta.backend.dto.response.TwinDeviceSnapshotResponse) sent.getAllValues().get(1).data();
        assertEquals("OFF", correction.currentState().path("power").asText());
    }

    @Test
    @DisplayName("Timeout: a newer state (e.g. a late device report) is never overwritten by the revert")
    void testSendCommand_TimeoutKeepsNewerState() {
        ReflectionTestUtils.setField(commandService, "transactionManager",
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        ReflectionTestUtils.setField(commandService, "commandTimeoutMs", 50L);
        ledDevice.setCurrentState(new HashMap<>(Map.of("power", "OFF")));
        Device reloaded = Device.builder().id(deviceId).deviceType("LED")
                .currentState(new HashMap<>(Map.of("power", "ON", "brightness", 40))).build();
        when(deviceRepository.findByIdWithNode(deviceId))
                .thenReturn(Optional.of(ledDevice))
                .thenReturn(Optional.of(reloaded));

        commandService.sendCommand(deviceId, "TURN_ON", new HashMap<>(), StateChangeSource.MANUAL).join();

        assertEquals("ON", reloaded.getCurrentState().get("power"));
        verify(deviceRepository, never()).save(reloaded);
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

    @Test
    @DisplayName("Voice: fast-return succeeds with SUCCESS within ~250ms when no hardware ACK")
    void testSendCommand_Voice_FastReturn() {
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(acDevice));

        Map<String, Object> params = new HashMap<>();
        params.put("temperature", 24);

        long start = System.currentTimeMillis();
        com.hesta.backend.dto.command.CommandResult result = commandService
                .sendCommand(deviceId, "SET_TEMPERATURE", params, StateChangeSource.VOICE)
                .join();
        long duration = System.currentTimeMillis() - start;

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("SUCCESS", result.getStatus());
        assertEquals("Dispatched to MQTT broker", result.getMessage());
        assertTrue(duration < 800, "Fast-return should complete well under 800ms, actual: " + duration + "ms");
    }

    @Test
    @DisplayName("Voice: supersedes previous in-flight command rather than rejecting or queuing")
    void testSendCommand_Voice_SupersedesPendingCommand() {
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        // First command pending
        var future1 = commandService.sendCommand(deviceId, "POWER_ON", new HashMap<>(), StateChangeSource.MANUAL);

        // Immediate voice command should supersede rather than being rejected
        com.hesta.backend.dto.command.CommandResult result2 = commandService
                .sendCommand(deviceId, "POWER_OFF", new HashMap<>(), StateChangeSource.VOICE)
                .join();

        assertNotNull(result2);
        assertTrue(result2.isSuccess());
        assertEquals("SUCCESS", result2.getStatus());
    }

    @Test
    @DisplayName("Gate: sendCommand registers commandId in commandDeviceMap")
    void testSendCommand_Gate_PopulatesCommandDeviceMap() {
        Device gateDevice = Device.builder()
                .id(deviceId)
                .name("Main Gate")
                .deviceType("GATE")
                .localId("gate_1")
                .node(EdgeNode.builder().id(UUID.randomUUID()).nodeCode("HESTA-GATE-01").build())
                .currentState(new HashMap<>())
                .build();
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(gateDevice));

        commandService.sendCommand(deviceId, "CLOSE", new HashMap<>(), StateChangeSource.VOICE);

        assertFalse(MqttDeviceCommandServiceImpl.commandDeviceMap.isEmpty());
        assertTrue(MqttDeviceCommandServiceImpl.commandDeviceMap.containsValue(deviceId));
    }

    @Test
    @DisplayName("SET_BRIGHTNESS rejected with DEVICE_OFFLINE and no MQTT/save when device status is OFFLINE, UNKNOWN, or ERROR")
    void testSendCommand_SetBrightness_RejectedWhenDeviceUnavailable() {
        for (DeviceStatus status : new DeviceStatus[]{DeviceStatus.OFFLINE, DeviceStatus.UNKNOWN, DeviceStatus.ERROR}) {
            ledDevice.setStatus(status);
            when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

            CommandResult result = commandService
                    .sendCommand(deviceId, "SET_BRIGHTNESS", Map.of("brightness", 80), StateChangeSource.MANUAL)
                    .join();

            assertNotNull(result);
            assertFalse(result.isSuccess(), "Expected failure for status " + status);
            assertEquals("DEVICE_OFFLINE", result.getErrorCode());
        }

        verify(mqttGateway, never()).sendToMqtt(anyString(), anyInt(), anyString());
        verify(deviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("SET_BRIGHTNESS rejected with DEVICE_OFFLINE and no MQTT/save when device is ONLINE but EdgeNode is OFFLINE")
    void testSendCommand_SetBrightness_RejectedWhenEdgeNodeOffline() {
        ledDevice.setStatus(DeviceStatus.ONLINE);
        ledDevice.getNode().setStatus(EdgeNodeStatus.OFFLINE);
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(ledDevice));

        CommandResult result = commandService
                .sendCommand(deviceId, "SET_BRIGHTNESS", Map.of("brightness", 80), StateChangeSource.MANUAL)
                .join();

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("DEVICE_OFFLINE", result.getErrorCode());
        verify(mqttGateway, never()).sendToMqtt(anyString(), anyInt(), anyString());
        verify(deviceRepository, never()).save(any());
    }
    @Test
    @DisplayName("Fan: SET_SPEED with level 1, 2, 3 should update speed, fan, power and payload")
    void testSendCommand_Fan_SpeedLevels() throws Exception {
        EdgeNode node = EdgeNode.builder()
                .id(UUID.fromString("a15a1879-a2e2-4640-800a-6bd30a90892c"))
                .nodeCode("HESTA-APARTMENT-00377b16-F1")
                .build();

        Device fanDevice = Device.builder()
                .id(deviceId)
                .name("Smart Fan")
                .deviceType("FAN")
                .localId("fan_1")
                .node(node)
                .currentState(new HashMap<>())
                .build();

        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(fanDevice));

        // 1. Test Mức 1 (Speed = 1)
        Map<String, Object> params1 = new HashMap<>();
        params1.put("speed", 1);
        commandService.sendCommand(deviceId, "SET_SPEED", params1, StateChangeSource.MANUAL);

        ArgumentCaptor<String> captor1 = ArgumentCaptor.forClass(String.class);
        verify(mqttGateway, atLeastOnce()).sendToMqtt(anyString(), anyInt(), captor1.capture());

        Map<String, Object> payload1 = objectMapper.readValue(captor1.getValue(), Map.class);
        assertEquals("SET_SPEED", payload1.get("action"));
        assertEquals(1, payload1.get("speed"));
        assertEquals("LOW", payload1.get("fan"));
        assertEquals(true, payload1.get("power"));
        assertEquals(1, fanDevice.getCurrentState().get("speed"));
        assertEquals("LOW", fanDevice.getCurrentState().get("fan"));
        assertEquals("ON", fanDevice.getCurrentState().get("power"));

        // 2. Test Mức 2 (Speed = 2)
        Thread.sleep(70);
        reset(mqttGateway);
        Map<String, Object> params2 = new HashMap<>();
        params2.put("speed", 2);
        commandService.sendCommand(deviceId, "SET_SPEED", params2, StateChangeSource.MANUAL);

        ArgumentCaptor<String> captor2 = ArgumentCaptor.forClass(String.class);
        verify(mqttGateway, atLeastOnce()).sendToMqtt(anyString(), anyInt(), captor2.capture());

        Map<String, Object> payload2 = objectMapper.readValue(captor2.getValue(), Map.class);
        assertEquals(2, payload2.get("speed"));
        assertEquals("MED", payload2.get("fan"));
        assertEquals(2, fanDevice.getCurrentState().get("speed"));

        // 3. Test Mức 3 (Speed = 3)
        Thread.sleep(70);
        reset(mqttGateway);
        Map<String, Object> params3 = new HashMap<>();
        params3.put("speed", 3);
        commandService.sendCommand(deviceId, "SET_SPEED", params3, StateChangeSource.MANUAL);

        ArgumentCaptor<String> captor3 = ArgumentCaptor.forClass(String.class);
        verify(mqttGateway, atLeastOnce()).sendToMqtt(anyString(), anyInt(), captor3.capture());

        Map<String, Object> payload3 = objectMapper.readValue(captor3.getValue(), Map.class);
        assertEquals(3, payload3.get("speed"));
        assertEquals("HIGH", payload3.get("fan"));
        assertEquals(3, fanDevice.getCurrentState().get("speed"));
    }

    @Test
    @DisplayName("Aperture Interlock: blocks repeated OPEN/CLOSE at limit or mid-transit and allows immediate emergency STOP")
    void testApertureInterlock_ProtectsMotorAndAllowsEmergencyStop() {
        EdgeNode node = EdgeNode.builder()
                .id(UUID.fromString("a15a1879-a2e2-4640-800a-6bd30a90892c"))
                .nodeCode("HESTA-APARTMENT-00377b16-F1")
                .build();

        for (String type : new String[]{"DOOR", "ROLLING_DOOR", "GATE", "GATE_MOTOR", "CURTAIN"}) {
            UUID id = UUID.randomUUID();
            Device door = Device.builder()
                    .id(id)
                    .name("Test " + type)
                    .deviceType(type)
                    .status(DeviceStatus.ONLINE)
                    .node(node)
                    .currentState(new HashMap<>(Map.of("state", "OPEN")))
                    .build();
            when(deviceRepository.findByIdWithNode(id)).thenReturn(Optional.of(door));

            CommandResult dupOpen = commandService.sendCommand(id, "OPEN", new HashMap<>(), StateChangeSource.MANUAL).join();
            assertFalse(dupOpen.isSuccess(), "Expected duplicate OPEN blocked for " + type);
            assertEquals("ALREADY_AT_OPEN_LIMIT", dupOpen.getErrorCode());

            door.setCurrentState(new HashMap<>(Map.of("state", "CLOSED")));
            CommandResult dupClose = commandService.sendCommand(id, "CLOSE", new HashMap<>(), StateChangeSource.MANUAL).join();
            assertFalse(dupClose.isSuccess(), "Expected duplicate CLOSE blocked for " + type);
            assertEquals("ALREADY_AT_CLOSE_LIMIT", dupClose.getErrorCode());

            door.setCurrentState(new HashMap<>(Map.of("state", "OPENING")));
            CommandResult midOpen = commandService.sendCommand(id, "OPEN", new HashMap<>(), StateChangeSource.MANUAL).join();
            assertFalse(midOpen.isSuccess(), "Expected mid-transit OPEN blocked for " + type);
            assertEquals("MOTOR_IN_TRANSIT", midOpen.getErrorCode());

            CommandResult midReverse = commandService.sendCommand(id, "CLOSE", new HashMap<>(), StateChangeSource.MANUAL).join();
            assertFalse(midReverse.isSuccess(), "Expected mid-transit CLOSE reverse blocked for " + type);
            assertEquals("MOTOR_IN_TRANSIT", midReverse.getErrorCode());
        }
        verify(mqttGateway, never()).sendToMqtt(anyString(), anyInt(), anyString());

        // Start a fresh CLOSED -> OPEN movement and immediately send emergency STOP (< 400ms)
        Device rolling = Device.builder()
                .id(deviceId)
                .name("Rolling Door")
                .deviceType("ROLLING_DOOR")
                .status(DeviceStatus.ONLINE)
                .node(node)
                .currentState(new HashMap<>(Map.of("state", "CLOSED")))
                .build();
        when(deviceRepository.findByIdWithNode(deviceId)).thenReturn(Optional.of(rolling));
        ReflectionTestUtils.setField(commandService, "commandTimeoutMs", 2000L);

        commandService.sendCommand(deviceId, "OPEN", new HashMap<>(), StateChangeSource.MANUAL);
        assertEquals("OPENING", rolling.getCurrentState().get("state"));

        // Immediate STOP bypasses anti-spam 400ms and supersedes active OPEN command
        var stopFuture = commandService.sendCommand(deviceId, "STOP", new HashMap<>(), StateChangeSource.MANUAL);
        assertEquals("STOPPED", rolling.getCurrentState().get("state"));
        verify(mqttGateway, atLeast(2)).sendToMqtt(anyString(), anyInt(), anyString());
        stopFuture.cancel(true);
    }
}

