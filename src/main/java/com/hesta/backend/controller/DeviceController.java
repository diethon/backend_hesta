package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> getDevices(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId) {
        List<DeviceResponse> devices = deviceService.getDevicesForHome(userDetails.getId(), homeId);
        return ResponseEntity.ok(ApiResponse.<List<DeviceResponse>>builder()
                .code(1000)
                .result(devices)
                .build());
    }
}
