package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AutomationEngineResponse {
    int evaluatedRules;
    int matchedRules;
    List<AutomationExecutionResponse> executions;
}
