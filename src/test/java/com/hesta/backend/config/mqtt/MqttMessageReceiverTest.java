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
    void stateMessageReleasesWaitingCommandBeforeTheDbWrite() {
        UUID nodeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        var waiting = new java.util.concurrent.CompletableFuture<com.hesta.backend.dto.command.CommandResult>();
        var active = (java.util.Map<UUID, java.util.concurrent.CompletableFuture<com.hesta.backend.dto.command.CommandResult>>)
                org.springframework.test.util.ReflectionTestUtils.getField(
                        com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl.class, "activeDeviceCommands");
        active.put(deviceId, waiting);
        // The DB write sees the command already done, i.e. the ACK did not wait for it.
        doAnswer(inv -> { org.junit.jupiter.api.Assertions.assertTrue(waiting.isDone()); return null; })
                .when(deviceService).updateDeviceStateFromMqtt(any(), any(), any());

        receiver.handleMessage(MessageBuilder.withPayload("{\"state\":\"opening\"}")
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nodeId + "/devices/" + deviceId + "/state")
                .build());

        verify(deviceService).updateDeviceStateFromMqtt(any(), any(), any());
        org.junit.jupiter.api.Assertions.assertTrue(waiting.join().isSuccess());
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

    @Test
    void commandAckWithStatusOkCompletesPendingCommandSuccessfully() {
        String commandId = UUID.randomUUID().toString();
        java.util.concurrent.CompletableFuture<com.hesta.backend.dto.command.CommandResult> future = new java.util.concurrent.CompletableFuture<>();
        com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl.pendingCommands.put(commandId, future);

        String payload = "{\"commandId\":\"" + commandId + "\",\"status\":\"OK\"}";
        receiver.handleMessage(MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/node-1/response")
                .build());

        org.junit.jupiter.api.Assertions.assertTrue(future.isDone());
        com.hesta.backend.dto.command.CommandResult result = future.join();
        org.junit.jupiter.api.Assertions.assertTrue(result.isSuccess());
        org.junit.jupiter.api.Assertions.assertEquals("OK", result.getStatus());
    }

    @Test
    void commandAckWithStatusClosingCompletesPendingCommandSuccessfully() {
        String commandId = UUID.randomUUID().toString();
        java.util.concurrent.CompletableFuture<com.hesta.backend.dto.command.CommandResult> future = new java.util.concurrent.CompletableFuture<>();
        com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl.pendingCommands.put(commandId, future);

        String payload = "{\"commandId\":\"" + commandId + "\",\"status\":\"CLOSING\"}";
        receiver.handleMessage(MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/node-1/response")
                .build());

        org.junit.jupiter.api.Assertions.assertTrue(future.isDone());
        com.hesta.backend.dto.command.CommandResult result = future.join();
        org.junit.jupiter.api.Assertions.assertTrue(result.isSuccess());
        org.junit.jupiter.api.Assertions.assertEquals("CLOSING", result.getStatus());
    }

    @Test
    void lateAckResolvesDeviceIdFromCommandDeviceMap() {
        String commandId = UUID.randomUUID().toString();
        UUID deviceId = UUID.randomUUID();
        com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl.commandDeviceMap.put(commandId, deviceId);

        String payload = "{\"commandId\":\"" + commandId + "\",\"status\":\"CLOSED\",\"state\":{\"state\":\"CLOSED\"}}";
        receiver.handleMessage(MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/node-1/response")
                .build());

        verify(deviceService).updateDeviceStateFromMqtt(isNull(), eq(deviceId.toString()), anyMap());
        org.junit.jupiter.api.Assertions.assertFalse(com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl.commandDeviceMap.containsKey(commandId));
    }
}
