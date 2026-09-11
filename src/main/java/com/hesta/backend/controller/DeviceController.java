package com.hesta.backend.controller;

import com.hesta.backend.dto.request.DeviceUpdateRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping("/homes/{homeId}/devices")
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> getDevicesByHome(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId) {

        List<DeviceResponse> result = deviceService.getDevicesByHome(userDetails.getId(), homeId);
        
        return ResponseEntity.ok(ApiResponse.<List<DeviceResponse>>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @GetMapping("/devices/{deviceId}")
    public ResponseEntity<ApiResponse<DeviceResponse>> getDeviceDetail(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        DeviceResponse result = deviceService.getDeviceDetail(userDetails.getId(), deviceId);

        return ResponseEntity.ok(ApiResponse.<DeviceResponse>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @PutMapping("/devices/{deviceId}")
    public ResponseEntity<ApiResponse<DeviceResponse>> updateDeviceConfig(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @Valid @RequestBody DeviceUpdateRequest request) {

        DeviceResponse result = deviceService.updateDeviceConfig(userDetails.getId(), deviceId, request);

        return ResponseEntity.ok(ApiResponse.<DeviceResponse>builder()
                .code(1000)
                .message("Cập nhật thiết bị thành công")
                .result(result)
                .build());
    }

    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<ApiResponse<Void>> removeDevice(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        deviceService.removeDevice(userDetails.getId(), deviceId);

        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .code(1000)
                .message("Xoá thiết bị thành công")
                .build());
    }

    @GetMapping("/devices/{deviceId}/history")
    public ResponseEntity<ApiResponse<List<com.hesta.backend.dto.response.DeviceStateHistoryResponse>>> getDeviceHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        List<com.hesta.backend.dto.response.DeviceStateHistoryResponse> result = deviceService.getDeviceHistory(userDetails.getId(), deviceId);

        return ResponseEntity.ok(ApiResponse.<List<com.hesta.backend.dto.response.DeviceStateHistoryResponse>>builder()
                .code(1000)
                .result(result)
                .build());
    }
}
