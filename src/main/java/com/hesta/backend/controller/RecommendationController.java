package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.RecommendationResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/recommendations")
@RequiredArgsConstructor
public class RecommendationController {
    private final RecommendationService recommendations;

    @GetMapping
    public ResponseEntity<ApiResponse<List<RecommendationResponse>>> list(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId) {
        return ok(recommendations.list(user.getId(), homeId));
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<RecommendationResponse>>> generate(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ok(recommendations.generate(user.getId(), homeId, from, to));
    }

    @PostMapping("/{recommendationId}/approve")
    public ResponseEntity<ApiResponse<RecommendationResponse>> approve(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId,
            @PathVariable UUID recommendationId) {
        return ok(recommendations.approve(user.getId(), homeId, recommendationId));
    }

    @PostMapping("/{recommendationId}/reject")
    public ResponseEntity<ApiResponse<RecommendationResponse>> reject(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId,
            @PathVariable UUID recommendationId) {
        return ok(recommendations.reject(user.getId(), homeId, recommendationId));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T result) {
        return ResponseEntity.ok(ApiResponse.<T>builder().code(1000).result(result).build());
    }
}
