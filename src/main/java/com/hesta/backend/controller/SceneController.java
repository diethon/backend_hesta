package com.hesta.backend.controller;

import com.hesta.backend.dto.request.CreateSceneRequest;
import com.hesta.backend.dto.request.ReorderSceneActionsRequest;
import com.hesta.backend.dto.request.SceneActionRequest;
import com.hesta.backend.dto.request.UpdateSceneRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.SceneActionResponse;
import com.hesta.backend.dto.response.SceneResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.SceneService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/scenes")
@RequiredArgsConstructor
public class SceneController {

    private final SceneService sceneService;

    @PostMapping
    public ResponseEntity<ApiResponse<SceneResponse>> createScene(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @Valid @RequestBody CreateSceneRequest request) {
        SceneResponse scene = sceneService.createScene(userDetails.getId(), homeId, request);
        return ResponseEntity.ok(ApiResponse.<SceneResponse>builder()
                .code(1000)
                .message("Tạo kịch bản thành công")
                .result(scene)
                .build());
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SceneResponse>>> getScenes(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId) {
        List<SceneResponse> scenes = sceneService.getScenesForHome(userDetails.getId(), homeId);
        return ResponseEntity.ok(ApiResponse.<List<SceneResponse>>builder()
                .code(1000)
                .result(scenes)
                .build());
    }

    @GetMapping("/{sceneId}")
    public ResponseEntity<ApiResponse<SceneResponse>> getScene(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID sceneId) {
        SceneResponse scene = sceneService.getScene(userDetails.getId(), homeId, sceneId);
        return ResponseEntity.ok(ApiResponse.<SceneResponse>builder()
                .code(1000)
                .result(scene)
                .build());
    }

    @PutMapping("/{sceneId}")
    public ResponseEntity<ApiResponse<SceneResponse>> updateScene(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID sceneId,
            @Valid @RequestBody UpdateSceneRequest request) {
        SceneResponse scene = sceneService.updateScene(userDetails.getId(), homeId, sceneId, request);
        return ResponseEntity.ok(ApiResponse.<SceneResponse>builder()
                .code(1000)
                .message("Cập nhật kịch bản thành công")
                .result(scene)
                .build());
    }

    @DeleteMapping("/{sceneId}")
    public ResponseEntity<ApiResponse<Void>> deleteScene(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID sceneId) {
        sceneService.deleteScene(userDetails.getId(), homeId, sceneId);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .code(1000)
                .message("Xóa kịch bản thành công")
                .build());
    }

    @PostMapping("/{sceneId}/actions")
    public ResponseEntity<ApiResponse<SceneActionResponse>> addAction(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID sceneId,
            @Valid @RequestBody SceneActionRequest request) {
        SceneActionResponse action = sceneService.addAction(userDetails.getId(), homeId, sceneId, request);
        return ResponseEntity.ok(ApiResponse.<SceneActionResponse>builder()
                .code(1000)
                .message("Thêm hành động thành công")
                .result(action)
                .build());
    }

    @DeleteMapping("/{sceneId}/actions/{actionId}")
    public ResponseEntity<ApiResponse<Void>> removeAction(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID sceneId,
            @PathVariable UUID actionId) {
        sceneService.removeAction(userDetails.getId(), homeId, sceneId, actionId);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .code(1000)
                .message("Xóa hành động thành công")
                .build());
    }

    @PutMapping("/{sceneId}/actions/reorder")
    public ResponseEntity<ApiResponse<SceneResponse>> reorderActions(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID sceneId,
            @Valid @RequestBody ReorderSceneActionsRequest request) {
        SceneResponse scene = sceneService.reorderActions(userDetails.getId(), homeId, sceneId, request);
        return ResponseEntity.ok(ApiResponse.<SceneResponse>builder()
                .code(1000)
                .message("Sắp xếp hành động thành công")
                .result(scene)
                .build());
    }
}
