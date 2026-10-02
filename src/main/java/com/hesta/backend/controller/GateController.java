package com.hesta.backend.controller;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.GateCommandRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.GateStateResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.GateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/devices/{deviceId}/gate")
@RequiredArgsConstructor
public class GateController {

    private final GateService gateService;

    @PostMapping("/command")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> sendCommand(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @Valid @RequestBody GateCommandRequest request) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return gateService.sendCommand(userId, deviceId, request)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Gửi lệnh điều khiển cổng thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/open")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> open(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return gateService.open(userId, deviceId)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Gửi lệnh mở cổng thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/close")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> close(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return gateService.close(userId, deviceId)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Gửi lệnh đóng cổng thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/stop")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> stop(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return gateService.stop(userId, deviceId)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Gửi lệnh dừng cổng thành công")
                        .result(result)
                        .build()));
    }

    @GetMapping("/state")
    public ResponseEntity<ApiResponse<GateStateResponse>> getState(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        GateStateResponse result = gateService.getState(userId, deviceId);
        return ResponseEntity.ok(ApiResponse.<GateStateResponse>builder()
                .code(1000)
                .message("Lấy trạng thái cổng thành công")
                .result(result)
                .build());
    }
}
