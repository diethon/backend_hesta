package com.hesta.backend.service;

import com.hesta.backend.dto.response.SceneExecutionResponse;

import java.util.List;
import java.util.UUID;

public interface SceneExecutionService {
    SceneExecutionResponse execute(UUID userId, UUID homeId, UUID sceneId);
    SceneExecutionResponse executeScheduled(UUID homeId, UUID sceneId);
    SceneExecutionResponse executeFromAutomation(UUID homeId, UUID sceneId);
    List<SceneExecutionResponse> history(UUID userId, UUID homeId, UUID sceneId);
}
