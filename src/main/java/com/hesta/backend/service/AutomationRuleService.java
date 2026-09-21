package com.hesta.backend.service;

import com.hesta.backend.dto.request.CreateAutomationRuleRequest;
import com.hesta.backend.dto.request.UpdateAutomationRuleRequest;
import com.hesta.backend.dto.response.AutomationExecutionResponse;
import com.hesta.backend.dto.response.AutomationRuleResponse;

import java.util.List;
import java.util.UUID;

public interface AutomationRuleService {
    AutomationRuleResponse create(UUID userId, UUID homeId, CreateAutomationRuleRequest request);
    List<AutomationRuleResponse> getAll(UUID userId, UUID homeId);
    AutomationRuleResponse get(UUID userId, UUID homeId, UUID ruleId);
    AutomationRuleResponse update(UUID userId, UUID homeId, UUID ruleId, UpdateAutomationRuleRequest request);
    AutomationRuleResponse setEnabled(UUID userId, UUID homeId, UUID ruleId, boolean enabled);
    void delete(UUID userId, UUID homeId, UUID ruleId);
    List<AutomationExecutionResponse> getExecutions(UUID userId, UUID homeId, UUID ruleId);
}
