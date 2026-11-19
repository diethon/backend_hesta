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

    /**
     * A command a person gave (app, web, voice): stored as a habit action (TOGGLE resolved to TURN_ON/TURN_OFF)
     * for the recommendation model. Device state and history are kept by the command service itself.
     */
    void recordUserCommand(java.util.UUID deviceId, String action, java.util.Map<String, Object> parameters,
                           java.util.Map<String, Object> previousState, StateChangeSource source);

    /** Power switched on the device itself (wall switch, remote), not by any command we sent. */
    void recordPhysicalToggle(Device device, java.util.Map<String, Object> previousState,
                              java.util.Map<String, Object> newState);

    void recordSensor(Device device, AutomationEventRequest event);

    void recordExecution(Home home, UUID userId, String eventType, UUID targetId, String status);

    void recordRecommendationFeedback(Home home, UUID userId, Device device, UUID recommendationId,
                                      String feedbackAction, String engineType, String modelVersion);
}
