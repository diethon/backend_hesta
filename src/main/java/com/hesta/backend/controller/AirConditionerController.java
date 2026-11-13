package com.hesta.backend.controller;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AirConditionerCommandRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.AirConditionerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/devices/{deviceId}/air-conditioner")
@RequiredArgsConstructor
public class AirConditionerController {

    private final AirConditionerService airConditionerService;

    @PostMapping("/command")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> sendCommand(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @Valid @RequestBody AirConditionerCommandRequest request) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return airConditionerService.sendCommand(userId, deviceId, request)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Gửi lệnh điều khiển điều hòa thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/power")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> setPower(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @RequestParam boolean power) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return airConditionerService.setPower(userId, deviceId, power)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Cập nhật nguồn điều hòa thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/temperature")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> setTemperature(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @RequestParam int temperature) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return airConditionerService.setTemperature(userId, deviceId, temperature)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Cài đặt nhiệt độ điều hòa thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/temperature-plus")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> temperaturePlus(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return airConditionerService.temperaturePlus(userId, deviceId)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Tăng nhiệt độ điều hòa thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/temperature-minus")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> temperatureMinus(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return airConditionerService.temperatureMinus(userId, deviceId)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Giảm nhiệt độ điều hòa thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/fan")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> setFan(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @RequestParam String fan) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return airConditionerService.setFan(userId, deviceId, fan)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Cài đặt quạt gió điều hòa thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/mode")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> setMode(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @RequestParam String mode) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return airConditionerService.setMode(userId, deviceId, mode)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Cài đặt chế độ điều hòa thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/swing")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> setSwing(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @RequestParam boolean enabled) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return airConditionerService.setSwing(userId, deviceId, enabled)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Cài đặt đảo gió điều hòa thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/timer")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> setTimer(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @RequestParam int hour,
            @RequestParam(defaultValue = "false") boolean halfHour) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return airConditionerService.setTimer(userId, deviceId, hour, halfHour)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Cài đặt hẹn giờ điều hòa thành công")
                        .result(result)
                        .build()));
    }

    @PostMapping("/timer/cancel")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> cancelTimer(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return airConditionerService.cancelTimer(userId, deviceId)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Hủy hẹn giờ điều hòa thành công")
                        .result(result)
                        .build()));
    }

    @GetMapping("/state")
    public CompletableFuture<ResponseEntity<ApiResponse<CommandResult>>> getState(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId) {

        UUID userId = userDetails != null ? userDetails.getId() : null;
        return airConditionerService.getState(userId, deviceId)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .message("Lấy trạng thái điều hòa thành công")
                        .result(result)
                        .build()));
    }
}
