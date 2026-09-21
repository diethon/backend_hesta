package com.hesta.backend.service;

import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.dto.response.AutomationEngineResponse;

import java.util.UUID;

public interface AutomationEngine {
    AutomationEngineResponse process(UUID homeId, AutomationEventRequest event);
}
