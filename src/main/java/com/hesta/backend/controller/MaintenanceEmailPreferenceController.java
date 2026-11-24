package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.entity.User;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.policy.AccessPolicyType;
import com.hesta.backend.security.policy.HomeAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/me/maintenance-email-preference")
@RequiredArgsConstructor
@HomeAccessPolicy(value = AccessPolicyType.AUTHENTICATED, description = "Authenticated user maintenance email preference")
public class MaintenanceEmailPreferenceController {

    private final UserRepository userRepository;

    public record PreferenceRequest(boolean enabled) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> get(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        User user = requireUser(userDetails);
        return ResponseEntity.ok(ApiResponse.<Map<String, Boolean>>builder()
                .code(1000)
                .result(Map.of("maintenanceEmailEnabled", user.isMaintenanceEmailEnabled()))
                .build());
    }

    @PutMapping
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> update(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody PreferenceRequest request) {
        User user = requireUser(userDetails);
        user.setMaintenanceEmailEnabled(request.enabled());
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.<Map<String, Boolean>>builder()
                .code(1000)
                .message(request.enabled()
                        ? "Đã bật nhận email thông báo bảo trì"
                        : "Đã tắt nhận email thông báo bảo trì")
                .result(Map.of("maintenanceEmailEnabled", user.isMaintenanceEmailEnabled()))
                .build());
    }

    private User requireUser(CustomUserDetails userDetails) {
        if (userDetails == null || userDetails.getId() == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return userRepository.findById(userDetails.getId())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }
}
