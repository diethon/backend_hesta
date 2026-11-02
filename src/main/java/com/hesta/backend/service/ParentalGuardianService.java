package com.hesta.backend.service;

import com.hesta.backend.dto.request.CreateParentalGuardianRequest;
import com.hesta.backend.dto.request.GuardianSimulateEventRequest;
import com.hesta.backend.dto.request.UpdateParentalGuardianRequest;
import com.hesta.backend.dto.response.ParentalGuardianLogResponse;
import com.hesta.backend.dto.response.ParentalGuardianResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ParentalGuardianService {
    ParentalGuardianResponse createRule(UUID userId, UUID homeId, CreateParentalGuardianRequest request);

    List<ParentalGuardianResponse> getRules(UUID userId, UUID homeId);

    ParentalGuardianResponse getRule(UUID userId, UUID homeId, UUID ruleId);

    ParentalGuardianResponse updateRule(UUID userId, UUID homeId, UUID ruleId, UpdateParentalGuardianRequest request);

    ParentalGuardianResponse setEnabled(UUID userId, UUID homeId, UUID ruleId, boolean enabled);

    void deleteRule(UUID userId, UUID homeId, UUID ruleId);

    ParentalGuardianResponse setParentAway(UUID userId, UUID homeId, UUID ruleId, boolean parentAway);

    ParentalGuardianResponse grantExtension(UUID userId, UUID homeId, UUID ruleId, int extraMinutes);

    ParentalGuardianResponse unlock(UUID userId, UUID homeId, UUID ruleId);

    ParentalGuardianResponse simulate(UUID userId, UUID homeId, UUID ruleId, GuardianSimulateEventRequest request);

    List<ParentalGuardianLogResponse> getLogs(UUID userId, UUID homeId, UUID ruleId);

    void onDeviceStateChanged(UUID homeId, UUID deviceId, Map<String, Object> currentState);

    void onCameraEvent(UUID homeId, UUID cameraDeviceId, String eventType, Map<String, Object> data);

    void checkDueSessions();
}
