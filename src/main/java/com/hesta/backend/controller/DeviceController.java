package com.hesta.backend.controller;

import com.hesta.backend.dto.request.DeviceUpdateRequest;
import com.hesta.backend.dto.request.ManualDeviceCommandRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.dto.response.ManualCommandResponse;
import com.hesta.backend.dto.response.ManualOverrideResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.DeviceService;
import com.hesta.backend.service.ManualControlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final ManualControlService manualControlService;

    @PostMapping("/devices/{deviceId}/commands")
    public ResponseEntity<ApiResponse<ManualCommandResponse>> command(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId, @Valid @RequestBody ManualDeviceCommandRequest request) {
        return ResponseEntity.ok(ApiResponse.<ManualCommandResponse>builder().code(1000)
                .result(manualControlService.command(userDetails.getId(), deviceId, request.getAction())).build());
    }

    @PostMapping("/devices/{deviceId}/automation/cancel")
    public ResponseEntity<ApiResponse<OffsetDateTime>> cancelAutomation(
            @AuthenticationPrincipal CustomUserDetails userDetails, @PathVariable UUID deviceId) {
        return ResponseEntity.ok(ApiResponse.<OffsetDateTime>builder().code(1000)
                .result(manualControlService.cancelAutomation(userDetails.getId(), deviceId)).build());
    }

    @GetMapping("/devices/{deviceId}/automation/overrides")
    public ResponseEntity<ApiResponse<List<ManualOverrideResponse>>> overrideHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails, @PathVariable UUID deviceId) {
        return ResponseEntity.ok(ApiResponse.<List<ManualOverrideResponse>>builder().code(1000)
                .result(manualControlService.history(userDetails.getId(), deviceId)).build());
    }

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

    @GetMapping("/rooms/{roomId}/devices")
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> getDevicesByRoom(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID roomId) {

        List<DeviceResponse> result = deviceService.getDevicesByRoom(userDetails.getId(), roomId);

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
