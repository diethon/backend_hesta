package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.EdgeNodeRepository;
import com.hesta.backend.repository.UserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    UserRepository userRepository;
    DeviceRepository deviceRepository;
    EdgeNodeRepository edgeNodeRepository;

    @GetMapping("/dashboard-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardStats() {
        long totalUsers = userRepository.count();
        long totalDevices = deviceRepository.count();
        long edgeNodes = edgeNodeRepository.count();

        Map<String, Object> stats = Map.of(
                "totalUsers", totalUsers,
                "activeDevices", totalDevices,
                "totalNodes", edgeNodes,
                "systemAlerts", 0,
                "uptime", "99.9%"
        );

        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .code(1000)
                .message("Lấy thống kê thành công")
                .result(stats)
                .build();

        return ResponseEntity.ok(response);
    }
}
