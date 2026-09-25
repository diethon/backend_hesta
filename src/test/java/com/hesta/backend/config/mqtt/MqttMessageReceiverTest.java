package com.hesta.backend.config.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.entity.Home;
import com.hesta.backend.enums.DeviceType;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.AutomationEngine;
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
    @Spy ObjectMapper mapper = new ObjectMapper();
    @InjectMocks MqttMessageReceiver receiver;

    @Test
    void validSensorMessageTriggersAutomationForTheDeviceHome() {
        UUID homeId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        when(devices.findById(deviceId)).thenReturn(Optional.of(Device.builder().id(deviceId)
                .home(Home.builder().id(homeId).build()).node(EdgeNode.builder().id(nodeId).build())
                .deviceType(DeviceType.SENSOR).build()));
        receiver.handleMessage(MessageBuilder.withPayload("{\"temperature\":31}")
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nodeId + "/devices/" + deviceId + "/sensor")
                .build());
        verify(engine).process(eq(homeId), argThat((AutomationEventRequest event) ->
                event.getSourceDeviceId().equals(deviceId) && event.getData().get("temperature").equals(31)));
    }

    @Test
    void invalidTopicNeverRunsAutomation() {
        receiver.handleMessage(MessageBuilder.withPayload("{\"temperature\":31}")
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/unknown/devices/unknown/state")
                .build());
        verifyNoInteractions(devices, engine);
    }
}
