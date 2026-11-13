package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.entity.BehaviorEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.repository.BehaviorEventRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.BehaviorEventRecorder;
import com.hesta.backend.service.execution.DeviceActionValues;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BehaviorEventRecorderImpl implements BehaviorEventRecorder {
    private final BehaviorEventRepository behaviorEvents;
    private final DeviceStateHistoryRepository deviceHistory;

    // Every MQTT report used to insert a row (~17k/day per sensor). Recommendations only use DEVICE_ACTION rows,
    // so keep at most one sensor row per device per interval, and only when the reported values changed.
    private static final long SENSOR_SAMPLE_SECONDS = 60;
    private final java.util.Map<UUID, Object[]> lastSensorSample = new java.util.concurrent.ConcurrentHashMap<>();
    private final DeviceRepository devices;
    private final UserRepository users;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCommand(Device device, String action, StateChangeSource source, CommandResult result) {
        recordCommand(device, null, action, source, result);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCommand(Device device, UUID userId, String action, StateChangeSource source, CommandResult result) {
        if (result == null || !result.isSuccess()) return;
        Device recordedDevice = devices.findById(device.getId()).orElse(device);
        User user = userId == null ? null : users.findById(userId).orElse(null);
        Map<String, Object> previous = recordedDevice.getCurrentState() == null
                ? Map.of() : new HashMap<>(recordedDevice.getCurrentState());
        Map<String, Object> current = new HashMap<>(previous);
        if (result.getAcknowledgedState() != null) current.putAll(result.getAcknowledgedState());
        deviceHistory.save(DeviceStateHistory.builder().device(recordedDevice).previousState(previous)
                .newState(current).source(source).isTest(false).build());
        behaviorEvents.save(BehaviorEvent.builder()
                .home(recordedDevice.getRoom() != null ? recordedDevice.getRoom().getHome() : recordedDevice.getHome())
                .device(recordedDevice)
                .room(recordedDevice.getRoom())
                .user(user)
                .eventType("DEVICE_ACTION")
                .action(action)
                .eventSource(source != null ? source.name() : "UNKNOWN")
                .previousState(previous)
                .currentState(current)
                .occurredAt(OffsetDateTime.now()).build());
        recordedDevice.setCurrentState(current);
        devices.save(recordedDevice);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUserCommand(UUID deviceId, String action, Map<String, Object> parameters,
                                  Map<String, Object> previousState, StateChangeSource source) {
        Device device = devices.findById(deviceId).orElse(null);
        if (device == null) return;
        Map<String, Object> previous = previousState == null ? Map.of() : new HashMap<>(previousState);
        saveAction(device, DeviceActionValues.habitAction(action, parameters, previous),
                source != null ? source.name() : "MANUAL", previous, device.getCurrentState());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPhysicalToggle(Device device, Map<String, Object> previousState, Map<String, Object> newState) {
        Object power = newState == null ? null : newState.get("power");
        if (power == null) return;
        saveAction(device, DeviceActionValues.isOn(power) ? "TURN_ON" : "TURN_OFF", "PHYSICAL",
                previousState == null ? Map.of() : previousState, newState);
    }

    private void saveAction(Device device, String action, String source, Map<String, Object> previous, Map<String, Object> current) {
        behaviorEvents.save(BehaviorEvent.builder()
                .home(device.getRoom() != null ? device.getRoom().getHome() : device.getHome())
                .device(device)
                .room(device.getRoom())
                .eventType("DEVICE_ACTION")
                .action(action)
                .eventSource(source)
                .previousState(new HashMap<>(previous))
                .currentState(current == null ? new HashMap<>() : new HashMap<>(current))
                .occurredAt(OffsetDateTime.now()).build());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSensor(Device device, AutomationEventRequest event) {
        if (event.isTest()) return;
        OffsetDateTime now = OffsetDateTime.now();
        int hash = java.util.Objects.hashCode(event.getData());
        Object[] last = lastSensorSample.get(device.getId());
        if (last != null && ((int) last[0] == hash
                || ((OffsetDateTime) last[1]).isAfter(now.minusSeconds(SENSOR_SAMPLE_SECONDS)))) {
            return;
        }
        lastSensorSample.put(device.getId(), new Object[]{hash, now});
        behaviorEvents.save(BehaviorEvent.builder()
                .home(device.getRoom() != null ? device.getRoom().getHome() : device.getHome())
                .device(device)
                .room(device.getRoom())
                .eventType(event.getEventType())
                .eventSource("SENSOR")
                .currentState(new HashMap<>(event.getData()))
                .occurredAt(event.getOccurredAt() == null ? OffsetDateTime.now() : event.getOccurredAt()).build());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordExecution(Home home, UUID userId, String eventType, UUID targetId, String status) {
        User user = userId == null ? null : users.findById(userId).orElse(null);
        Map<String, Object> state = Map.of("targetId", targetId.toString(), "status", status);
        behaviorEvents.save(BehaviorEvent.builder().home(home).user(user)
                .eventType(eventType).action(status)
                .eventSource(user != null ? "MANUAL" : "AUTOMATION_EXECUTION".equals(eventType) ? "AUTOMATION" : "SCHEDULE")
                .currentState(state).occurredAt(OffsetDateTime.now()).build());
        if (user != null) {
            behaviorEvents.save(BehaviorEvent.builder().home(home).user(user)
                    .eventType("USER_INTERACTION").action("EXECUTE_SCENE")
                    .eventSource("WEB").currentState(state).occurredAt(OffsetDateTime.now()).build());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRecommendationFeedback(Home home, UUID userId, Device device, UUID recommendationId,
                                              String feedbackAction, String engineType, String modelVersion) {
        User user = userId == null ? null : users.findById(userId).orElse(null);
        Map<String, Object> feedbackState = new HashMap<>();
        feedbackState.put("recommendationId", recommendationId != null ? recommendationId.toString() : null);
        feedbackState.put("engineType", engineType != null ? engineType : "ML");
        feedbackState.put("modelVersion", modelVersion);
        feedbackState.put("feedback", feedbackAction);

        behaviorEvents.save(BehaviorEvent.builder()
                .home(home)
                .user(user)
                .device(device)
                .room(device != null ? device.getRoom() : null)
                .eventType("RECOMMENDATION_FEEDBACK")
                .action(feedbackAction)
                .eventSource("USER_FEEDBACK")
                .currentState(feedbackState)
                .occurredAt(OffsetDateTime.now())
                .build());
    }
}
