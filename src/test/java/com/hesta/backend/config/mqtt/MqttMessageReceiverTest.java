package com.hesta.backend.config.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.support.TwinHealthTestSupport;
import org.springframework.context.ApplicationEventPublisher;
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
    @Mock ApplicationEventPublisher applicationEvents;
    @Spy TwinSnapshotMapper twinSnapshotMapper = TwinHealthTestSupport.mapper(new ObjectMapper());
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

    @Test
    void sensorTelemetryMessageWithMetricTypeTriggersAutomationWithMappedMetric() {
        UUID homeId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();
        when(devices.findById(deviceId)).thenReturn(Optional.of(Device.builder().id(deviceId)
                .node(EdgeNode.builder().id(nodeId).home(home).build())
                .room(Room.builder().home(home).build())
                .deviceType("TEMPERATURE_SENSOR").build()));

        String payload = "{\"nodeId\":\"" + nodeId + "\",\"deviceId\":\"" + deviceId + "\",\"metricType\":\"temperature\",\"value\":32.5,\"unit\":\"C\"}";
        receiver.handleMessage(MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nodeId + "/devices/" + deviceId + "/telemetry")
                .build());

        verify(telemetryService).processTelemetry(anyString(), any());
        verify(engine).process(eq(homeId), argThat((AutomationEventRequest event) ->
                event.getSourceDeviceId().equals(deviceId)
                        && "SENSOR".equals(event.getEventType())
                        && new java.math.BigDecimal("32.5").equals(event.getData().get("temperature"))
                        && new java.math.BigDecimal("32.5").equals(event.getData().get("temp"))
                        && new java.math.BigDecimal("32.5").equals(event.getData().get("value"))));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = DeviceStatus.class, names = {"ONLINE", "OFFLINE"})
    void connectionStatusMessagePublishesStatusWithoutLastSeen(DeviceStatus status) {
        UUID nodeId = UUID.randomUUID(), deviceId = UUID.randomUUID(), homeId = UUID.randomUUID();
        var home = Home.builder().id(homeId).build();
        var device = Device.builder().id(deviceId).room(Room.builder().home(home).build())
                .status(status == DeviceStatus.ONLINE ? DeviceStatus.OFFLINE : DeviceStatus.ONLINE).build();
        when(devices.findById(deviceId)).thenReturn(Optional.of(device));
        receiver.handleMessage(MessageBuilder.withPayload(status.name())
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nodeId + "/devices/" + deviceId + "/status").build());
        verify(applicationEvents).publishEvent(argThat((DeviceStateChangedEvent event) ->
                event.homeId().equals(homeId) && event.payload().status() == status && event.payload().lastSeen() == null));
    }

    @Test
    void flatMapTelemetryExtractsSafetyMetricsAndPreservesStateUpdate() {
        UUID nodeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        String payload = "{\"nodeId\":\"" + nodeId + "\",\"deviceId\":\"" + deviceId + "\",\"humidity\":65.0,\"temperature\":29.5,\"gas_ppm\":850.0,\"smoke\":120.0}";

        receiver.handleMessage(MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nodeId + "/devices/" + deviceId + "/telemetry")
                .build());

        // 1. Existing behavior preserved: full map sent to updateDeviceStateFromMqtt
        verify(deviceService).updateDeviceStateFromMqtt(eq(nodeId.toString()), eq(deviceId.toString()), argThat(map ->
                Double.valueOf(65.0).equals(map.get("humidity"))
                        && Double.valueOf(29.5).equals(map.get("temperature"))
                        && Double.valueOf(850.0).equals(map.get("gas_ppm"))
                        && Double.valueOf(120.0).equals(map.get("smoke"))
        ));

        // 2. Safety metrics extracted and dispatched to telemetryService
        verify(telemetryService).processTelemetry(contains("/telemetry"), argThat(p ->
                "gas_ppm".equals(p.getMetricType()) && new java.math.BigDecimal("850.0").equals(p.getValue())));
        verify(telemetryService).processTelemetry(contains("/telemetry"), argThat(p ->
                "smoke".equals(p.getMetricType()) && new java.math.BigDecimal("120.0").equals(p.getValue())));
        verify(telemetryService).processTelemetry(contains("/telemetry"), argThat(p ->
                "temperature".equals(p.getMetricType()) && new java.math.BigDecimal("29.5").equals(p.getValue())));
    }
}
