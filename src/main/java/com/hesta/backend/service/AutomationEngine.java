package com.hesta.backend.service;

import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.dto.response.AutomationEngineResponse;
import com.hesta.backend.dto.response.AutomationTestResponse;
import com.hesta.backend.dto.response.AutomationExecutionResponse;

import java.util.UUID;

public interface AutomationEngine {
    AutomationEngineResponse process(UUID homeId, AutomationEventRequest event);
    AutomationTestResponse testRule(UUID homeId, UUID ruleId, AutomationEventRequest event);
    AutomationExecutionResponse executeScheduled(UUID homeId, UUID ruleId);
}
