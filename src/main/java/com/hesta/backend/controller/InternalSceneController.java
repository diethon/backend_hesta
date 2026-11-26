package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.VoiceSceneExecutionResult;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneAction;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.SceneRepository;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.service.impl.VoiceSceneExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controller nội bộ dành cho voice-service để lấy danh sách và kích hoạt/tắt Kịch bản (Scenes).
 * Được bảo vệ bởi InternalApiKeyFilter (/internal/**).
 */
@RestController
@RequestMapping("/internal/scenes")
@RequiredArgsConstructor
@Slf4j
public class InternalSceneController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SceneRepository sceneRepository;
    private final VoiceSceneExecutionService voiceSceneExecutionService;
    private final JwtTokenProvider jwtTokenProvider;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getScenes(@RequestParam(required = false) UUID homeId) {
        List<Scene> scenes = homeId != null
                ? sceneRepository.findAllByHomeIdOrderByNameAsc(homeId)
                : sceneRepository.findAll();

        // Sắp xếp ổn định theo createdAt ASC, sau đó name ASC để thứ tự "kịch bản 1, 2, 3" không bị nhảy
        List<Scene> ordered = scenes.stream()
                .sorted(Comparator
                        .comparing(Scene::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Scene::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        List<Map<String, Object>> result = new java.util.ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Scene s = ordered.get(i);
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", s.getId().toString());
            dto.put("homeId", s.getHome() != null ? s.getHome().getId().toString() : "");
            dto.put("name", s.getName());
            dto.put("description", s.getDescription());
            dto.put("enabled", s.isEnabled());
            dto.put("index", i + 1);
            List<Map<String, Object>> actionDtos = s.getActions().stream()
                    .sorted(Comparator.comparingInt(SceneAction::getOrder))
                    .map(a -> {
                        Map<String, Object> ad = new LinkedHashMap<>();
                        ad.put("deviceId", a.getTargetDevice() != null ? a.getTargetDevice().getId().toString() : "");
                        ad.put("deviceName", a.getTargetDevice() != null ? a.getTargetDevice().getName() : "");
                        ad.put("deviceType", a.getTargetDevice() != null ? a.getTargetDevice().getDeviceType() : "");
                        ad.put("action", a.getAction());
                        ad.put("order", a.getOrder());
                        return ad;
                    })
                    .toList();
            dto.put("actions", actionDtos);
            result.add(dto);
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/{sceneId}/execute")
    public ResponseEntity<ApiResponse<VoiceSceneExecutionResult>> executeScene(
            @PathVariable UUID sceneId,
            @RequestHeader(value = "X-User-Id", required = false) String xUserIdHeader,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> payload) {

        UUID resolvedUserId = resolveUserId(xUserIdHeader, authHeader, payload);
        UUID resolvedHomeId = resolveHomeId(payload);

        log.info("Internal voice request to ACTIVATE scene {} (homeId={}, userId={})", sceneId, resolvedHomeId, resolvedUserId);
        VoiceSceneExecutionResult result = voiceSceneExecutionService.executeFromVoice(resolvedUserId, resolvedHomeId, sceneId);

        return ResponseEntity.ok(ApiResponse.<VoiceSceneExecutionResult>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @PostMapping("/{sceneId}/deactivate")
    public ResponseEntity<ApiResponse<VoiceSceneExecutionResult>> deactivateScene(
            @PathVariable UUID sceneId,
            @RequestHeader(value = "X-User-Id", required = false) String xUserIdHeader,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> payload) {

        UUID resolvedUserId = resolveUserId(xUserIdHeader, authHeader, payload);
        UUID resolvedHomeId = resolveHomeId(payload);

        log.info("Internal voice request to DEACTIVATE scene {} (homeId={}, userId={})", sceneId, resolvedHomeId, resolvedUserId);
        VoiceSceneExecutionResult result = voiceSceneExecutionService.deactivateFromVoice(resolvedUserId, resolvedHomeId, sceneId);

        return ResponseEntity.ok(ApiResponse.<VoiceSceneExecutionResult>builder()
                .code(1000)
                .result(result)
                .build());
    }

    private UUID resolveUserId(String xUserIdHeader, String authHeader, Map<String, Object> payload) {
        if (StringUtils.hasText(xUserIdHeader)) {
            try {
                return UUID.fromString(xUserIdHeader.trim());
            } catch (IllegalArgumentException e) {
                throw new AppException(ErrorCode.INVALID_REQUEST);
            }
        }
        if (StringUtils.hasText(authHeader) && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            if (jwtTokenProvider.validateToken(token)) {
                return jwtTokenProvider.getUserIdFromToken(token);
            }
        }
        if (payload != null && payload.get("userId") != null && !payload.get("userId").toString().isBlank()) {
            try {
                return UUID.fromString(payload.get("userId").toString().trim());
            } catch (IllegalArgumentException e) {
                throw new AppException(ErrorCode.INVALID_REQUEST);
            }
        }
        return null;
    }

    private UUID resolveHomeId(Map<String, Object> payload) {
        if (payload != null && payload.get("homeId") != null && !payload.get("homeId").toString().isBlank()) {
            try {
                return UUID.fromString(payload.get("homeId").toString().trim());
            } catch (IllegalArgumentException e) {
                throw new AppException(ErrorCode.INVALID_REQUEST);
            }
        }
        return null;
    }
}
