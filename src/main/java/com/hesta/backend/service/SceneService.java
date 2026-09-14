package com.hesta.backend.service;

import com.hesta.backend.dto.request.CreateSceneRequest;
import com.hesta.backend.dto.request.ReorderSceneActionsRequest;
import com.hesta.backend.dto.request.SceneActionRequest;
import com.hesta.backend.dto.request.UpdateSceneRequest;
import com.hesta.backend.dto.response.SceneActionResponse;
import com.hesta.backend.dto.response.SceneResponse;

import java.util.List;
import java.util.UUID;

public interface SceneService {
    SceneResponse createScene(UUID authenticatedUserId, UUID homeId, CreateSceneRequest request);
    List<SceneResponse> getScenesForHome(UUID authenticatedUserId, UUID homeId);
    SceneResponse getScene(UUID authenticatedUserId, UUID homeId, UUID sceneId);
    SceneResponse updateScene(UUID authenticatedUserId, UUID homeId, UUID sceneId, UpdateSceneRequest request);
    void deleteScene(UUID authenticatedUserId, UUID homeId, UUID sceneId);

    SceneActionResponse addAction(
            UUID authenticatedUserId,
            UUID homeId,
            UUID sceneId,
            SceneActionRequest request);
    void removeAction(UUID authenticatedUserId, UUID homeId, UUID sceneId, UUID sceneActionId);
    SceneResponse reorderActions(
            UUID authenticatedUserId,
            UUID homeId,
            UUID sceneId,
            ReorderSceneActionsRequest request);
}
