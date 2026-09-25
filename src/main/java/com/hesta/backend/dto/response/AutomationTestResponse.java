package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AutomationTestResponse {
    UUID ruleId;
    boolean matched;
    List<RuleActionResponse> proposedActions;
}
