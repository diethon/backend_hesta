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
import com.hesta.backend.service.SecurityEventService;
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
        when(devices.findByIdWithNode(deviceId)).thenReturn(Optional.of(Device.builder().id(deviceId)
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
        // AC "temperature" is a setpoint, not a safety reading
        verify(telemetryService, never()).processTelemetry(anyString(), any());
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
        // Completion is async; it must finish while the DB write is still blocked, i.e. the ACK did not wait for it.
        doAnswer(inv -> { org.junit.jupiter.api.Assertions.assertTrue(waiting.get(2, java.util.concurrent.TimeUnit.SECONDS).isSuccess()); return null; })
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
    void motionZeroReadingIgnoresStaleSavedMotionState() {
        SecurityEventService securityEvents = mock(SecurityEventService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(receiver, "securityEventService", securityEvents);
        Device sensor = motionSensor(java.util.Map.of("MOTION_SENSOR", 1, "motion", true));

        sendMotionTelemetry(sensor, 0);

        verify(securityEvents, never()).recordMotion(any());
    }

    @Test
    void motionDetectedReadingRecordsSecurityEvent() {
        SecurityEventService securityEvents = mock(SecurityEventService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(receiver, "securityEventService", securityEvents);
        Device sensor = motionSensor(java.util.Map.of("MOTION_SENSOR", 0, "motion", false));

        sendMotionTelemetry(sensor, 1);

        verify(securityEvents).recordMotion(sensor);
    }

    private Device motionSensor(java.util.Map<String, Object> savedState) {
        Home home = Home.builder().id(UUID.randomUUID()).build();
        Device sensor = Device.builder().id(UUID.randomUUID())
                .node(EdgeNode.builder().id(UUID.randomUUID()).home(home).build())
                .room(Room.builder().home(home).build())
                .deviceType("MOTION_SENSOR")
                .currentState(new java.util.HashMap<>(savedState))
                .build();
        when(devices.findByIdWithNode(sensor.getId())).thenReturn(Optional.of(sensor));
        return sensor;
    }

    private void sendMotionTelemetry(Device sensor, int value) {
        String payload = "{\"deviceId\":\"" + sensor.getId() + "\",\"metricType\":\"motion\",\"value\":" + value + "}";
        receiver.handleMessage(MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/node-1/devices/" + sensor.getId() + "/sensor")
                .build());
    }

    @Test
    void sensorTelemetryMessageWithMetricTypeTriggersAutomationWithMappedMetric() {
        UUID homeId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Home home = Home.builder().id(homeId).build();
        when(devices.findByIdWithNode(deviceId)).thenReturn(Optional.of(Device.builder().id(deviceId)
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
        var realtimeEventPublisher = mock(com.hesta.backend.realtime.publisher.RealtimeEventPublisher.class);
        org.springframework.test.util.ReflectionTestUtils.setField(receiver, "realtimeEventPublisher", realtimeEventPublisher);
        receiver.handleMessage(MessageBuilder.withPayload(status.name())
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nodeId + "/devices/" + deviceId + "/status").build());
        // No transaction here, so the receiver broadcasts the snapshot itself instead of relying on AFTER_COMMIT listeners.
        verify(realtimeEventPublisher).publish(argThat(event ->
                event.type() == com.hesta.backend.realtime.model.RealtimeEventType.DEVICE_STATE_CHANGED
                        && event.homeId().equals(homeId)
                        && event.data() instanceof com.hesta.backend.dto.response.TwinDeviceSnapshotResponse snapshot
                        && snapshot.status() == status && snapshot.lastSeen() == null));
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

    @Test
    void flatMapTelemetryWithTempKeyNormalizesMetricTypeToTemperature() {
        UUID nodeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        String payload = "{\"nodeId\":\"" + nodeId + "\",\"deviceId\":\"" + deviceId + "\",\"temp\":55.0}";

        receiver.handleMessage(MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nodeId + "/devices/" + deviceId + "/telemetry")
                .build());

        verify(telemetryService).processTelemetry(contains("/telemetry"), argThat(p ->
                "temperature".equals(p.getMetricType())
                        && new java.math.BigDecimal("55.0").equals(p.getValue())
                        && "°C".equals(p.getUnit())));
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

    @Test
    @SuppressWarnings("unchecked")
    void ackWithoutCommandIdCompletesActiveCommandByTopicDeviceId() throws Exception {
        UUID deviceId = UUID.randomUUID();
        var waiting = new java.util.concurrent.CompletableFuture<com.hesta.backend.dto.command.CommandResult>();
        var active = (java.util.Map<UUID, java.util.concurrent.CompletableFuture<com.hesta.backend.dto.command.CommandResult>>)
                org.springframework.test.util.ReflectionTestUtils.getField(
                        com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl.class, "activeDeviceCommands");
        active.put(deviceId, waiting);

        String payload = "{\"status\":\"OK\",\"state\":{\"power\":\"OFF\"}}";
        receiver.handleMessage(MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/node-1/devices/" + deviceId + "/ack")
                .build());

        // completeDeviceCommand completes asynchronously, so wait instead of checking isDone() immediately
        org.junit.jupiter.api.Assertions.assertTrue(waiting.get(2, java.util.concurrent.TimeUnit.SECONDS).isSuccess());
        verify(deviceService).updateDeviceStateFromMqtt(isNull(), eq(deviceId.toString()), anyMap());
    }
}
