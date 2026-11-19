package com.hesta.backend.controller;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.HomeAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Controller dành riêng cho các lệnh nội bộ từ voice-service.
 * Được bảo vệ bởi InternalApiKeyFilter (không bắt buộc JWT người dùng).
 * Đối với nhà có thành viên phân quyền CUSTOM, yêu cầu định danh người dùng
 * qua header X-User-Id hoặc Authorization Bearer JWT.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
@Slf4j
public class InternalDeviceController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final DeviceRepository deviceRepository;
    private final DeviceCommandService deviceCommandService;
    private final HomeAuthorizationService homeAuthorizationService;
    private final HomeMemberRepository homeMemberRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @GetMapping("/devices")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getDevices(@RequestParam(required = false) UUID homeId) {
        try {
            log.info("Internal request to get devices for homeId: {}", homeId);
            List<Device> devices;

            if (homeId != null) {
                devices = deviceRepository.findByHomeId(homeId);
            } else {
                devices = deviceRepository.findAll();
            }

            List<DeviceResponse> response = devices.stream()
                    .filter(d -> !d.isDeleted())
                    .map(DeviceResponse::fromEntity)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting devices: ", e);
            return ResponseEntity.status(500).body("Error: " + e.getMessage() + " - " + e.getClass().getName());
        }
    }

    @PostMapping("/devices/{deviceId}/actions")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> executeAction(
            @PathVariable UUID deviceId,
            @RequestHeader(value = "X-User-Id", required = false) String xUserIdHeader,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody Map<String, Object> payload) {

        UUID resolvedUserId = resolveUserId(xUserIdHeader, authHeader, payload);

        if (resolvedUserId != null) {
            homeAuthorizationService.requireDeviceAccess(resolvedUserId, deviceId, AccessLevel.CONTROL);
        } else {
            // Unauthenticated voice command: verify target home does NOT have any CUSTOM member
            Device device = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

            if (device.getHome() != null) {
                boolean hasCustomMembers = homeMemberRepository.findByHomeId(device.getHome().getId())
                        .stream()
                        .anyMatch(m -> m.getStatus() == MemberStatus.ACTIVE
                                && m.getRole() != HomeRole.OWNER
                                && m.getAccessScope() == AccessScope.CUSTOM);
                if (hasCustomMembers) {
                    log.warn("Voice command rejected: device {} belongs to home {} with CUSTOM members, but no user identity was provided",
                            deviceId, device.getHome().getId());
                    throw new AppException(ErrorCode.UNAUTHORIZED);
                }
            }
        }

        String action = (String) payload.get("action");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) payload.get("parameters");

        log.info("Internal request to execute action {} on device {}", action, deviceId);

        return deviceCommandService.sendCommand(deviceId, action, parameters, StateChangeSource.VOICE)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .result(result)
                        .build()));
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

        if (payload != null && payload.get("userId") != null) {
            try {
                return UUID.fromString(payload.get("userId").toString());
            } catch (IllegalArgumentException e) {
                throw new AppException(ErrorCode.INVALID_REQUEST);
            }
        }

        return null;
    }
}
