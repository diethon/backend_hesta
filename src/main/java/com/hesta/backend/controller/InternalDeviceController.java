package com.hesta.backend.controller;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

/**
 * Controller dành riêng cho các lệnh nội bộ từ voice-service.
 * Được bảo vệ bởi InternalApiKeyFilter (không cần JWT).
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
@Slf4j
public class InternalDeviceController {

    private final DeviceRepository deviceRepository;
    private final DeviceCommandService deviceCommandService;

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
                    .filter(d -> !d.isDeleted()) // Chắc chắn thiết bị chưa bị xóa
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
            @RequestBody Map<String, Object> payload) {
            
        String action = (String) payload.get("action");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) payload.get("parameters");
        
        log.info("Internal request to execute action {} on device {}", action, deviceId);

        // Gọi lệnh với nguồn là VOICE
        return deviceCommandService.sendCommand(deviceId, action, parameters, StateChangeSource.VOICE)
                .thenApply(result -> ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                        .code(1000)
                        .result(result)
                        .build()));
    }
}
