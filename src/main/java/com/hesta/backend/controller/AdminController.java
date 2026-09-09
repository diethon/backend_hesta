package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    @GetMapping("/dashboard-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardStats() {
        // Dummy data for now
        Map<String, Object> stats = Map.of(
                "totalUsers", 124,
                "activeDevices", 892,
                "systemAlerts", 3,
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
