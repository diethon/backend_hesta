package com.hesta.backend.controller;

import com.hesta.backend.dto.request.AdminCreateDeviceRequest;
import com.hesta.backend.dto.response.AdminDeviceCreatedResponse;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.dto.response.QrInfoResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.DeviceOnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/devices")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDeviceController {

    private final DeviceOnboardingService onboardingService;

    @PostMapping
    public ResponseEntity<ApiResponse<AdminDeviceCreatedResponse>> createDevice(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody AdminCreateDeviceRequest request) {

        AdminDeviceCreatedResponse result = onboardingService.adminCreateDevice(userDetails.getId(), request);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<AdminDeviceCreatedResponse>builder()
                        .code(1000)
                        .message("Device created successfully")
                        .result(result)
                        .build()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceResponse>> getDevice(
            @PathVariable UUID id) {

        DeviceResponse result = onboardingService.getDeviceById(id);

        return ResponseEntity.ok(
                ApiResponse.<DeviceResponse>builder()
                        .code(1000)
                        .result(result)
                        .build()
        );
    }

    @GetMapping("/{id}/qr")
    public ResponseEntity<ApiResponse<QrInfoResponse>> getDeviceQr(
            @PathVariable UUID id) {

        QrInfoResponse result = onboardingService.getDeviceQr(id);

        return ResponseEntity.ok(
                ApiResponse.<QrInfoResponse>builder()
                        .code(1000)
                        .result(result)
                        .build()
        );
    }

    @PostMapping("/{id}/qr/regenerate")
    public ResponseEntity<ApiResponse<QrInfoResponse>> regenerateQr(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID id) {

        QrInfoResponse result = onboardingService.regenerateQr(userDetails.getId(), id);

        return ResponseEntity.ok(
                ApiResponse.<QrInfoResponse>builder()
                        .code(1000)
                        .message("QR code regenerated successfully")
                        .result(result)
                        .build()
        );
    }
}
