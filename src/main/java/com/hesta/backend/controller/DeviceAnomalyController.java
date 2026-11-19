package com.hesta.backend.controller;

import com.hesta.backend.dto.request.ResolveAnomalyRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.DeviceAnomalyResponse;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.SeverityLevel;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.policy.AccessPolicyType;
import com.hesta.backend.security.policy.HomeAccessPolicy;
import com.hesta.backend.service.DeviceAnomalyManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/anomalies")
@RequiredArgsConstructor
@HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Home anomalies management")
public class DeviceAnomalyController {

    private final DeviceAnomalyManagementService anomalyManagementService;

    @GetMapping
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "List anomalies in home with DB-level pagination")
    public ResponseEntity<ApiResponse<Page<DeviceAnomalyResponse>>> listAnomalies(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @RequestParam(required = false) AnomalyStatus status,
            @RequestParam(required = false) SeverityLevel severity,
            @PageableDefault(size = 20, sort = "detectedAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<DeviceAnomalyResponse> page = anomalyManagementService.listAnomalies(
                userDetails != null ? userDetails.getId() : null,
                homeId,
                status,
                severity,
                pageable
        );

        return ResponseEntity.ok(ApiResponse.<Page<DeviceAnomalyResponse>>builder()
                .code(1000)
                .result(page)
                .build());
    }

    @PutMapping("/{id}/acknowledge")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Acknowledge active anomaly")
    public ResponseEntity<ApiResponse<DeviceAnomalyResponse>> acknowledgeAnomaly(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID id) {

        DeviceAnomalyResponse response = anomalyManagementService.acknowledgeAnomaly(
                userDetails != null ? userDetails.getId() : null,
                homeId,
                id
        );

        return ResponseEntity.ok(ApiResponse.<DeviceAnomalyResponse>builder()
                .code(1000)
                .message("Đã xác nhận sự cố bất thường")
                .result(response)
                .build());
    }

    @PutMapping("/{id}/resolve")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Resolve anomaly with optional notes")
    public ResponseEntity<ApiResponse<DeviceAnomalyResponse>> resolveAnomaly(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ResolveAnomalyRequest request) {

        DeviceAnomalyResponse response = anomalyManagementService.resolveAnomaly(
                userDetails != null ? userDetails.getId() : null,
                homeId,
                id,
                request
        );

        return ResponseEntity.ok(ApiResponse.<DeviceAnomalyResponse>builder()
                .code(1000)
                .message("Đã xử lý sự cố bất thường thành công")
                .result(response)
                .build());
    }
}
