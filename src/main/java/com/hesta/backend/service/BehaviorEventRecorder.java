package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.dto.request.AutomationEventRequest;
import java.util.UUID;

public interface BehaviorEventRecorder {
    void recordCommand(Device device, DeviceAction action, StateChangeSource source, CommandResult result);
    void recordSensor(Device device, AutomationEventRequest event);
    void recordExecution(Home home, UUID userId, String eventType, UUID targetId, String status);
}
