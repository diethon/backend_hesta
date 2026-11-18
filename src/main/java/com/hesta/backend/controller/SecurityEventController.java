package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.SecurityEventResponse;
import com.hesta.backend.enums.SecurityEventStatus;
import com.hesta.backend.enums.SecurityEventType;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.policy.AccessPolicyType;
import com.hesta.backend.security.policy.HomeAccessPolicy;
import com.hesta.backend.service.SecurityEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/security-events")
@RequiredArgsConstructor
public class SecurityEventController {

    private final SecurityEventService securityEventService;

    @GetMapping
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "List security event history of a home")
    public ResponseEntity<ApiResponse<Page<SecurityEventResponse>>> list(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @RequestParam(required = false) SecurityEventStatus status,
            @RequestParam(required = false) SecurityEventType eventType,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.<Page<SecurityEventResponse>>builder()
                .code(1000)
                .result(securityEventService.list(userId(userDetails), homeId, status, eventType, pageable))
                .build());
    }

    @PutMapping("/{id}/confirm")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Owner confirms a real security threat")
    public ResponseEntity<ApiResponse<SecurityEventResponse>> confirm(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.<SecurityEventResponse>builder()
                .code(1000)
                .message("Đã xác nhận sự kiện an ninh")
                .result(securityEventService.updateStatus(userId(userDetails), homeId, id, SecurityEventStatus.CONFIRMED))
                .build());
    }

    @PutMapping("/{id}/dismiss")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Owner dismisses a false alarm")
    public ResponseEntity<ApiResponse<SecurityEventResponse>> dismiss(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.<SecurityEventResponse>builder()
                .code(1000)
                .message("Đã đánh dấu báo động giả")
                .result(securityEventService.updateStatus(userId(userDetails), homeId, id, SecurityEventStatus.DISMISSED))
                .build());
    }

    private static UUID userId(CustomUserDetails userDetails) {
        return userDetails != null ? userDetails.getId() : null;
    }
}
