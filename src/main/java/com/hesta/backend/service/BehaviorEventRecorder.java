package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.enums.StateChangeSource;

import java.util.UUID;

public interface BehaviorEventRecorder {
    void recordCommand(Device device, String action, StateChangeSource source, CommandResult result);

    void recordCommand(Device device, UUID userId, String action, StateChangeSource source, CommandResult result);

    void recordSensor(Device device, AutomationEventRequest event);

    void recordExecution(Home home, UUID userId, String eventType, UUID targetId, String status);

    void recordRecommendationFeedback(Home home, UUID userId, Device device, UUID recommendationId,
                                      String feedbackAction, String engineType, String modelVersion);
}
