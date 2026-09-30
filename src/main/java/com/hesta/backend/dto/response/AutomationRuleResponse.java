package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AutomationRuleResponse {
    UUID id;
    UUID homeId;
    String name;
    String description;
    String triggerType;
    boolean enabled;
    List<RuleConditionResponse> conditions;
    List<RuleActionResponse> actions;
    OffsetDateTime createdAt;
    OffsetDateTime updatedAt;
}
