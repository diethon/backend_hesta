package com.hesta.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class AutomationExecutionResponse {
    UUID id;
    UUID ruleId;
    String ruleName;
    String triggerSource;
    String status;
    boolean test;
    OffsetDateTime matchedAt;
    OffsetDateTime startedAt;
    OffsetDateTime completedAt;
    JsonNode resultDetail;
}
