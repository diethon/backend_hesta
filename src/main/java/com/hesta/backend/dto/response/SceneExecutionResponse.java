package com.hesta.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class SceneExecutionResponse {
    private UUID id;
    private UUID sceneId;
    private String triggerSource;
    private String status;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private JsonNode resultDetail;
}
