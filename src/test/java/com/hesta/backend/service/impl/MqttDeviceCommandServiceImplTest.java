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
    @DisplayName("LED: action SET_RGB should remain SET_RGB in MQTT payload")
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
}
