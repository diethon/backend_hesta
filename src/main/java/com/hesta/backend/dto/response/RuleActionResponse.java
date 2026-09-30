package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class RuleActionResponse {
    UUID id;
    UUID deviceId;
    String deviceName;
    UUID sceneId;
    String sceneName;
    String action;
    Map<String, Object> parameters;
    int order;
}
