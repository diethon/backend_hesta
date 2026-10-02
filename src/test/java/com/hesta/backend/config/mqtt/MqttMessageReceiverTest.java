package com.hesta.backend.config.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.AutomationEngine;
import com.hesta.backend.service.DeviceService;
import com.hesta.backend.service.TelemetryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MqttMessageReceiverTest {
    @Mock DeviceRepository devices;
    @Mock AutomationEngine engine;
    @Mock TelemetryService telemetryService;
    @Mock DeviceService deviceService;
    @Spy ObjectMapper mapper = new ObjectMapper();
    @InjectMocks MqttMessageReceiver receiver;

    @Test
    void validSensorMessageTriggersAutomationForTheDeviceHome() {
        UUID homeId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();
        when(devices.findById(deviceId)).thenReturn(Optional.of(Device.builder().id(deviceId)
                .node(EdgeNode.builder().id(nodeId).home(home).build())
                .room(Room.builder().home(home).build())
                .deviceType("SENSOR").build()));
        receiver.handleMessage(MessageBuilder.withPayload("{\"temperature\":31}")
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nodeId + "/devices/" + deviceId + "/sensor")
                .build());
        verify(engine).process(eq(homeId), argThat((AutomationEventRequest event) ->
                event.getSourceDeviceId().equals(deviceId) && event.getData().get("temperature").equals(31)));
    }

    @Test
    void invalidTopicNeverRunsAutomation() {
        receiver.handleMessage(MessageBuilder.withPayload("{\"temperature\":31}")
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/invalid/topic/format")
                .build());
        verifyNoInteractions(devices, engine);
    }

    @Test
    void acTelemetryMessageUpdatesDeviceState() {
        UUID nodeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        String payload = "{\"nodeId\":\"" + nodeId + "\",\"deviceId\":\"" + deviceId + "\",\"deviceType\":\"AIR_CONDITIONER\",\"power\":true,\"temperature\":26,\"mode\":\"COOL\",\"fan\":\"AUTO\"}";

        receiver.handleMessage(MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nodeId + "/devices/" + deviceId + "/telemetry")
                .build());

        verify(deviceService).updateDeviceStateFromMqtt(eq(nodeId.toString()), eq(deviceId.toString()), argThat(map ->
                Boolean.TRUE.equals(map.get("power")) && Integer.valueOf(26).equals(map.get("temperature")) && "COOL".equals(map.get("mode"))));
    }

    @Test
    void deviceStatusOnlineUpdatesStatus() {
        UUID nodeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Device device = Device.builder().id(deviceId).status(com.hesta.backend.enums.DeviceStatus.OFFLINE).build();
        when(devices.findById(deviceId)).thenReturn(Optional.of(device));

        receiver.handleMessage(MessageBuilder.withPayload("ONLINE")
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nodeId + "/devices/" + deviceId + "/status")
                .build());

        verify(devices).save(argThat(d -> d.getStatus() == com.hesta.backend.enums.DeviceStatus.ONLINE));
    }
}
