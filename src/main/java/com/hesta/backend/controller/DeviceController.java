package com.hesta.backend.controller;

import com.hesta.backend.dto.request.DeviceUpdateRequest;
import com.hesta.backend.dto.request.ManualDeviceCommandRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.dto.response.DeviceStateHistoryResponse;
import com.hesta.backend.dto.response.ManualCommandResponse;
import com.hesta.backend.dto.response.ManualOverrideResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.DeviceService;
import com.hesta.backend.service.ManualControlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.dto.command.CommandResult;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1")

public class DeviceController {

    private final DeviceService deviceService;
    @Autowired
    private ManualControlService manualControlService;
    @Autowired
    private com.hesta.backend.service.DeviceOnboardingService onboardingService;
    @Autowired(required = false)
    private com.hesta.backend.service.ManualOverrideService manualOverrideService;
    @Autowired(required = false)
    private com.hesta.backend.service.DeviceProfilingService deviceProfilingService;
    @Autowired(required = false)
    private com.hesta.backend.service.DeviceHealthService deviceHealthService;
    @Autowired(required = false)
    private com.hesta.backend.shadow.DeviceShadowService deviceShadowService;

    @GetMapping("/devices/qr/{token}")
    public ResponseEntity<ApiResponse<com.hesta.backend.dto.response.DevicePublicPreviewResponse>> resolveQrToken(
            @PathVariable String token) {
        com.hesta.backend.dto.response.DevicePublicPreviewResponse result = onboardingService.resolveQrToken(token);
        return ResponseEntity.ok(ApiResponse.<com.hesta.backend.dto.response.DevicePublicPreviewResponse>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @PostMapping("/devices/{deviceId}/claim")
    public ResponseEntity<ApiResponse<DeviceResponse>> claimDevice(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @Valid @RequestBody com.hesta.backend.dto.request.DeviceClaimRequest request) {
        if (userDetails == null) {
            throw new com.hesta.backend.exception.AppException(com.hesta.backend.exception.ErrorCode.UNAUTHENTICATED);
        }
        DeviceResponse result = onboardingService.claimDevice(userDetails.getId(), deviceId, request);
        return ResponseEntity.ok(ApiResponse.<DeviceResponse>builder()
                .code(1000)
                .message("Device claimed successfully")
                .result(result)
                .build());
    }



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
        if (userDetails == null) {
            throw new com.hesta.backend.exception.AppException(com.hesta.backend.exception.ErrorCode.UNAUTHENTICATED);
        }
        return ResponseEntity.ok(ApiResponse.<OffsetDateTime>builder().code(1000)
                .result(manualControlService.cancelAutomation(userDetails.getId(), deviceId)).build());
    }

    @PostMapping("/devices/{deviceId}/automation/resume")
    public ResponseEntity<ApiResponse<Void>> resumeAutomation(
            @AuthenticationPrincipal CustomUserDetails userDetails, @PathVariable UUID deviceId) {
        if (userDetails == null) {
            throw new com.hesta.backend.exception.AppException(com.hesta.backend.exception.ErrorCode.UNAUTHENTICATED);
        }
        manualControlService.resumeAutomation(userDetails.getId(), deviceId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().code(1000)
                .message("Đã khôi phục tự động hóa").build());
    }

    @PreAuthorize("@deviceAccessValidator.canAccessDevice(principal.id, #deviceId)")
    @PostMapping("/devices/{deviceId}/automation/extend")
    public ResponseEntity<ApiResponse<OffsetDateTime>> extendAutomation(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @RequestBody(required = false) Map<String, Object> requestBody) {
        if (userDetails == null) {
            throw new com.hesta.backend.exception.AppException(com.hesta.backend.exception.ErrorCode.UNAUTHENTICATED);
        }
        if (manualOverrideService == null) {
            throw new RuntimeException("Manual override service not available");
        }
        int minutes = 60;
        if (requestBody != null && requestBody.containsKey("hours")) {
            try {
                minutes = (int) Math.round(Double.parseDouble(requestBody.get("hours").toString()) * 60);
            } catch (Exception ignored) {}
        } else if (requestBody != null && requestBody.containsKey("minutes")) {
            try {
                minutes = Integer.parseInt(requestBody.get("minutes").toString());
            } catch (Exception ignored) {}
        }
        OffsetDateTime until = manualOverrideService.extendOverride(deviceId, userDetails.getId(), minutes);
        return ResponseEntity.ok(ApiResponse.<OffsetDateTime>builder().code(1000)
                .message("Đã gia hạn khóa tự động hóa thêm " + minutes + " phút")
                .result(until).build());
    }

    @GetMapping("/devices/{deviceId}/automation/overrides")
    public ResponseEntity<ApiResponse<List<ManualOverrideResponse>>> overrideHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails, @PathVariable UUID deviceId) {
        return ResponseEntity.ok(ApiResponse.<List<ManualOverrideResponse>>builder().code(1000)
                .result(manualControlService.history(userDetails.getId(), deviceId)).build());
    }

    private final DeviceCommandService deviceCommandService;

    public DeviceController(DeviceService deviceService, @Qualifier("mqttDeviceCommandServiceImpl") DeviceCommandService deviceCommandService) {
        this.deviceService = deviceService;
        this.deviceCommandService = deviceCommandService;
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

    @GetMapping({"/homes/{homeId}/device-capabilities", "/homes/{homeId}/devices/capabilities"})
    public ResponseEntity<ApiResponse<com.hesta.backend.dto.response.HomeCapabilitiesResponse>> getHomeCapabilities(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId) {

        com.hesta.backend.dto.response.HomeCapabilitiesResponse result = deviceService.getHomeCapabilities(userDetails.getId(), homeId);

        return ResponseEntity.ok(ApiResponse.<com.hesta.backend.dto.response.HomeCapabilitiesResponse>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @GetMapping("/devices/{deviceId}/capabilities")
    public ResponseEntity<ApiResponse<com.hesta.backend.dto.response.DeviceCapabilityInfoResponse>> getDeviceCapabilities(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        com.hesta.backend.dto.response.DeviceCapabilityInfoResponse result = deviceService.getDeviceCapabilities(userDetails.getId(), deviceId);

        return ResponseEntity.ok(ApiResponse.<com.hesta.backend.dto.response.DeviceCapabilityInfoResponse>builder()
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
    public ResponseEntity<ApiResponse<List<DeviceStateHistoryResponse>>> getDeviceHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        List<DeviceStateHistoryResponse> result = deviceService.getDeviceHistory(userDetails.getId(), deviceId);

        return ResponseEntity.ok(ApiResponse.<List<DeviceStateHistoryResponse>>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @GetMapping(value = "/devices/{deviceId}/history/export", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> exportDeviceHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {
        byte[] csv = deviceService.exportDeviceHistoryCsv(userDetails.getId(), deviceId);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"device_audit_" + deviceId + ".csv\"")
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(csv);
    }

    @GetMapping(value = "/devices/{deviceId}/history/export/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportDeviceHistoryExcel(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        byte[] excel = deviceService.exportDeviceHistoryExcel(userDetails.getId(), deviceId, from, to);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"device_audit_" + deviceId + ".xlsx\"")
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(excel);
    }

    @GetMapping(value = "/homes/{homeId}/devices/history/export", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> exportHomeDeviceHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId) {
        byte[] csv = deviceService.exportHomeHistoryCsv(userDetails.getId(), homeId);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"home_audit_" + homeId + ".csv\"")
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(csv);
    }

    @PreAuthorize("@deviceAccessValidator.canAccessDevice(principal.id, #deviceId)")
    @GetMapping("/devices/{deviceId}/analytics/uptime")
    public ResponseEntity<ApiResponse<com.hesta.backend.dto.response.DeviceUptimeResponse>> getDeviceUptime(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @RequestParam(defaultValue = "7") int days) {
        com.hesta.backend.dto.response.DeviceUptimeResponse result = deviceProfilingService.getDeviceUptime(deviceId, days);
        return ResponseEntity.ok(ApiResponse.<com.hesta.backend.dto.response.DeviceUptimeResponse>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @PreAuthorize("@deviceAccessValidator.canAccessDevice(principal.id, #deviceId)")
    @GetMapping("/devices/{deviceId}/health")
    public ResponseEntity<ApiResponse<com.hesta.backend.dto.response.DeviceHealthResponse>> getDeviceHealth(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {
        com.hesta.backend.dto.response.DeviceHealthResponse result = deviceHealthService.getDeviceHealth(deviceId);
        return ResponseEntity.ok(ApiResponse.<com.hesta.backend.dto.response.DeviceHealthResponse>builder()
                .code(1000)
                .result(result)
                .build());
    }

@PreAuthorize("@deviceAccessValidator.canAccessDevice(principal.id, #deviceId)")
    @PostMapping("/devices/{deviceId}/command")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> sendCommand(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @RequestBody Map<String, Object> request) {

        String actionStr = request.getOrDefault("action", "").toString();
        
        Map<String, Object> params;
        if (request.get("parameters") instanceof Map<?, ?> mapParams) {
            params = (Map<String, Object>) mapParams;
        } else {
            params = new HashMap<>(request);
            params.remove("action");
        }
        return deviceCommandService.sendCommand(deviceId, actionStr, params, StateChangeSource.MANUAL)
                .thenApply(result -> {
                    if (result.isSuccess() && manualOverrideService != null && userDetails != null) {
                        try {
                            manualOverrideService.recordOverride(deviceId, userDetails.getId(), 30);
                        } catch (Exception ex) {
                            // Do not fail command result on override failure
                        }
                    }
                    return ResponseEntity.ok(ApiResponse.<CommandResult>builder().result(result).build());
                });
    }

@PreAuthorize("@deviceAccessValidator.canAccessRoom(principal.id, #roomId)")
    @PostMapping("/rooms/{roomId}/command")
    public CompletableFuture<ResponseEntity<ApiResponse<List<CommandResult>>>> sendRoomCommand(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID roomId,
            @RequestBody Map<String, Object> request) {

        String actionStr = request.getOrDefault("action", "").toString();
        
        Map<String, Object> params;
        if (request.get("parameters") instanceof Map<?, ?> mapParams) {
            params = (Map<String, Object>) mapParams;
        } else {
            params = new HashMap<>(request);
            params.remove("action");
        }
        return deviceCommandService.sendRoomCommand(roomId, actionStr, params, StateChangeSource.MANUAL)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<List<CommandResult>>builder().result(result).build()));
    }

    @GetMapping("/devices/{deviceId}/shadow")
    public ResponseEntity<ApiResponse<com.hesta.backend.shadow.DeviceShadowDto>> getDeviceShadow(
            @PathVariable UUID deviceId) {
        if (deviceShadowService == null) {
            return ResponseEntity.ok(ApiResponse.<com.hesta.backend.shadow.DeviceShadowDto>builder().build());
        }
        com.hesta.backend.shadow.DeviceShadowDto shadow = deviceShadowService.getShadow(deviceId);
        return ResponseEntity.ok(ApiResponse.<com.hesta.backend.shadow.DeviceShadowDto>builder()
                .code(1000)
                .result(shadow)
                .build());
    }

    @PutMapping("/devices/{deviceId}/shadow/desired")
    public ResponseEntity<ApiResponse<com.hesta.backend.shadow.DeviceShadowDto>> updateDesiredShadow(
            @PathVariable UUID deviceId,
            @RequestBody Map<String, Object> desiredState) {
        if (deviceShadowService == null) {
            return ResponseEntity.ok(ApiResponse.<com.hesta.backend.shadow.DeviceShadowDto>builder().build());
        }
        com.hesta.backend.shadow.DeviceShadowDto shadow = deviceShadowService.updateDesiredState(deviceId, desiredState);
        return ResponseEntity.ok(ApiResponse.<com.hesta.backend.shadow.DeviceShadowDto>builder()
                .code(1000)
                .message("Đã cập nhật Desired State cho Device Shadow")
                .result(shadow)
                .build());
    }

    @PostMapping("/devices/{deviceId}/shadow/reconcile")
    public ResponseEntity<ApiResponse<com.hesta.backend.shadow.DeviceShadowDto>> reconcileDeviceShadow(
            @PathVariable UUID deviceId) {
        if (deviceShadowService == null) {
            return ResponseEntity.ok(ApiResponse.<com.hesta.backend.shadow.DeviceShadowDto>builder().build());
        }
        com.hesta.backend.shadow.DeviceShadowDto shadow = deviceShadowService.reconcile(deviceId);
        return ResponseEntity.ok(ApiResponse.<com.hesta.backend.shadow.DeviceShadowDto>builder()
                .code(1000)
                .message("Đã kích hoạt Reconcile Device Shadow")
                .result(shadow)
                .build());
    }
}
