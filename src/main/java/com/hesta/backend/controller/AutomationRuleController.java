package com.hesta.backend.controller;

import com.hesta.backend.dto.request.*;
import com.hesta.backend.dto.response.*;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/automation-rules")
@RequiredArgsConstructor
public class AutomationRuleController {
    private final AutomationRuleService ruleService;
    private final AutomationEngine automationEngine;
    private final HomeAuthorizationService homeAuthorizationService;

    @PostMapping
    public ResponseEntity<ApiResponse<AutomationRuleResponse>> create(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId,
            @Valid @RequestBody CreateAutomationRuleRequest request) {
        return ok("Tạo luật tự động hóa thành công", ruleService.create(user.getId(), homeId, request));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AutomationRuleResponse>>> getAll(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId) {
        return ok(null, ruleService.getAll(user.getId(), homeId));
    }

    @GetMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<AutomationRuleResponse>> get(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId, @PathVariable UUID ruleId) {
        return ok(null, ruleService.get(user.getId(), homeId, ruleId));
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<AutomationRuleResponse>> update(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId, @PathVariable UUID ruleId,
            @Valid @RequestBody UpdateAutomationRuleRequest request) {
        return ok("Cập nhật luật tự động hóa thành công", ruleService.update(user.getId(), homeId, ruleId, request));
    }

    @PatchMapping("/{ruleId}/enabled")
    public ResponseEntity<ApiResponse<AutomationRuleResponse>> setEnabled(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId, @PathVariable UUID ruleId,
            @RequestParam boolean enabled) {
        return ok("Cập nhật trạng thái luật thành công", ruleService.setEnabled(user.getId(), homeId, ruleId, enabled));
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId, @PathVariable UUID ruleId) {
        ruleService.delete(user.getId(), homeId, ruleId);
        return ok("Xóa luật tự động hóa thành công", null);
    }

    @GetMapping("/{ruleId}/executions")
    public ResponseEntity<ApiResponse<List<AutomationExecutionResponse>>> executions(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId, @PathVariable UUID ruleId) {
        return ok(null, ruleService.getExecutions(user.getId(), homeId, ruleId));
    }

    @PostMapping("/events")
    public ResponseEntity<ApiResponse<AutomationEngineResponse>> processEvent(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID homeId,
            @Valid @RequestBody AutomationEventRequest request) {
        homeAuthorizationService.requireAccess(user.getId(), homeId);
        return ok("Đã xử lý sự kiện tự động hóa", automationEngine.process(homeId, request));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(String message, T result) {
        return ResponseEntity.ok(ApiResponse.<T>builder().code(1000).message(message).result(result).build());
    }
}
