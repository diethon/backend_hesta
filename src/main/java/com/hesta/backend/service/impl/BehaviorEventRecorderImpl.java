package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.entity.BehaviorEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.repository.BehaviorEventRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.BehaviorEventRecorder;
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
    private final DeviceRepository devices;
    private final UserRepository users;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCommand(Device device, DeviceAction action, StateChangeSource source, CommandResult result) {
        if (result == null || !result.isSuccess()) return;
        Device recordedDevice = devices.findById(device.getId()).orElse(device);
        Map<String, Object> previous = recordedDevice.getCurrentState() == null
                ? Map.of() : new HashMap<>(recordedDevice.getCurrentState());
        Map<String, Object> current = new HashMap<>(previous);
        if (result.getAcknowledgedState() != null) current.putAll(result.getAcknowledgedState());
        deviceHistory.save(DeviceStateHistory.builder().device(recordedDevice).previousState(previous)
                .newState(current).source(source).isTest(false).build());
        behaviorEvents.save(BehaviorEvent.builder().home(recordedDevice.getRoom().getHome()).device(recordedDevice).room(recordedDevice.getRoom())
                .eventType("DEVICE_ACTION").action(action.name()).eventSource(source.name())
                .previousState(previous).currentState(current).occurredAt(OffsetDateTime.now()).build());
        recordedDevice.setCurrentState(current);
        devices.save(recordedDevice);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSensor(Device device, AutomationEventRequest event) {
        if (event.isTest()) return;
        behaviorEvents.save(BehaviorEvent.builder().home(device.getRoom().getHome()).device(device).room(device.getRoom())
                .eventType(event.getEventType()).eventSource("SENSOR")
                .currentState(new HashMap<>(event.getData()))
                .occurredAt(event.getOccurredAt() == null ? OffsetDateTime.now() : event.getOccurredAt()).build());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordExecution(Home home, UUID userId, String eventType, UUID targetId, String status) {
        User user = userId == null ? null : users.findById(userId).orElse(null);
        Map<String, Object> state = Map.of("targetId", targetId.toString(), "status", status);
        behaviorEvents.save(BehaviorEvent.builder().home(home).user(user)
                .eventType(eventType).action(status).eventSource(user == null ? "SCHEDULE" : "MANUAL")
                .currentState(state).occurredAt(OffsetDateTime.now()).build());
        if (user != null) {
            behaviorEvents.save(BehaviorEvent.builder().home(home).user(user)
                    .eventType("USER_INTERACTION").action("EXECUTE_SCENE")
                    .eventSource("WEB").currentState(state).occurredAt(OffsetDateTime.now()).build());
        }
    }
}
