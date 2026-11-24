package com.hesta.backend.service;

import com.hesta.backend.dto.response.SceneExecutionResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface SceneExecutionService {
    SceneExecutionResponse execute(UUID userId, UUID homeId, UUID sceneId);
    /** Starts the run and returns it as PENDING at once; progress and the result arrive as SCENE_EXECUTION_UPDATED. */
    SceneExecutionResponse executeAsync(UUID userId, UUID homeId, UUID sceneId);
    /** Runs the scene once after {@code delaySeconds}; replaces any earlier pending run of it. Returns when it will run. */
    OffsetDateTime runLater(UUID userId, UUID homeId, UUID sceneId, int delaySeconds);
    void cancelRunLater(UUID userId, UUID homeId, UUID sceneId);
    /** Drops a pending run without access checks (the scene is being deleted). */
    void cancelRunLater(UUID sceneId);
    OffsetDateTime pendingRunAt(UUID sceneId);
    SceneExecutionResponse executeScheduled(UUID homeId, UUID sceneId);
    SceneExecutionResponse executeFromAutomation(UUID homeId, UUID sceneId);
    List<SceneExecutionResponse> history(UUID userId, UUID homeId, UUID sceneId);
}
