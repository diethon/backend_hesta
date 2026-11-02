package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class RecommendationResponse {
    private UUID id;
    private UUID homeId;
    private UUID deviceId;
    private String deviceName;
    private Map<String, Object> triggerCondition;
    private Map<String, Object> proposedAction;
    private String explanation;
    private double confidence;
    private String status;
    private String engineType;
    private String modelVersion;
    private Boolean eligible;
    private OffsetDateTime createdAt;
    private OffsetDateTime resolvedAt;
}
