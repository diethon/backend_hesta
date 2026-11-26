package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.maintenance.assistant.MaintenanceAssistantMetricsService;
import com.hesta.backend.maintenance.assistant.MaintenanceAssistantRolloutService;
import com.hesta.backend.security.policy.AccessPolicyType;
import com.hesta.backend.security.policy.HomeAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/maintenance-assistant")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@HomeAccessPolicy(value = AccessPolicyType.PLATFORM_ADMIN, description = "Admin maintenance assistant metrics and kill switch")
public class AdminMaintenanceAssistantController {

    private final MaintenanceAssistantRolloutService rolloutService;
    private final MaintenanceAssistantMetricsService metricsService;

    public record KillSwitchRequest(boolean killSwitchActive) {
    }

    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<MaintenanceAssistantMetricsService.MetricsResponse>> getMetrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(ok(null, metricsService.computeMetrics(from, to)));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<MaintenanceAssistantRolloutService.AssistantStatusDto>> getStatus() {
        return ResponseEntity.ok(ok(null, rolloutService.getStatus()));
    }

    @PutMapping("/kill-switch")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> setKillSwitch(@RequestBody KillSwitchRequest request) {
        boolean active = rolloutService.setKillSwitch(request.killSwitchActive());
        return ResponseEntity.ok(ok(
                active ? "Đã BẬT công tắc khẩn cấp — tạm dừng toàn bộ LLM ngay lập tức"
                        : "Đã TẮT công tắc khẩn cấp — khôi phục hoạt động AI mặc định",
                Map.of("killSwitchActive", active)));
    }

    private static <T> ApiResponse<T> ok(String message, T result) {
        return ApiResponse.<T>builder().code(1000).message(message).result(result).build();
    }
}
