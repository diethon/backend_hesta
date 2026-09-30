package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class RecommendationResponse {
    UUID id;
    UUID homeId;
    UUID deviceId;
    String deviceName;
    Map<String, Object> triggerCondition;
    Map<String, Object> proposedAction;
    String explanation;
    double confidence;
    String status;
    OffsetDateTime createdAt;
    OffsetDateTime resolvedAt;
}
