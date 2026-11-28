package com.hesta.backend.dto.response;

import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Kết quả thực thi / tắt kịch bản qua Voice AI kèm thống kê chi tiết về xung đột và trạng thái thiết bị.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceSceneExecutionResult {
    private UUID sceneId;
    private String sceneName;
    /** ACTIVATE hoặc DEACTIVATE */
    private String mode;
    /** SUCCESS, PARTIAL, ALREADY_IN_STATE, FAILED, SKIPPED */
    private String status;
    private boolean success;
    private int totalDevices;
    private int executedCount;
    private int alreadyInStateCount;
    private int conflictResolvedCount;
    private int overriddenCount;
    private String overriddenSceneName;
    private int offlineCount;
    private int failedCount;
    private int skippedCount;
    private String errorCode;
    private ArrayNode resultDetail;
}
