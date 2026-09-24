package com.hesta.backend.controller;

import com.hesta.backend.dto.request.LedCommandRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DeviceCommandController {

    private final DeviceCommandService deviceCommandService;

    @PostMapping({"/api/devices/{deviceId}/command", "/api/v1/devices/{deviceId}/command"})
    public ResponseEntity<ApiResponse<Void>> sendCommand(
            @PathVariable UUID deviceId,
            @RequestBody LedCommandRequest request
    ) {
        log.info("Received device command request for deviceId={}: action={}", deviceId, request != null ? request.getAction() : null);

        deviceCommandService.sendCommand(deviceId, request);

        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .code(1000)
                .message("Gửi lệnh điều khiển thành công")
                .build());
    }
}
