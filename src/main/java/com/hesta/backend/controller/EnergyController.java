package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.DeviceEnergyBreakdown;
import com.hesta.backend.dto.response.EnergyAnalyticsResponse;
import com.hesta.backend.dto.response.EnergySummaryResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.EnergyAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EnergyController {

    private final EnergyAnalyticsService energyAnalyticsService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.energy.demandresponse.DemandResponseService demandResponseService;

    @GetMapping("/homes/{homeId}/energy")
    public ResponseEntity<ApiResponse<EnergyAnalyticsResponse>> getHomeEnergyAnalytics(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @RequestParam(required = false, defaultValue = "30D") String period) {

        EnergyAnalyticsResponse result = energyAnalyticsService.getHomeEnergyAnalytics(userDetails.getId(), homeId, period);

        return ResponseEntity.ok(ApiResponse.<EnergyAnalyticsResponse>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @GetMapping("/homes/{homeId}/energy/summary")
    public ResponseEntity<ApiResponse<EnergySummaryResponse>> getHomeEnergySummary(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID homeId,
            @RequestParam(required = false, defaultValue = "30D") String period) {

        EnergyAnalyticsResponse full = energyAnalyticsService.getHomeEnergyAnalytics(userDetails.getId(), homeId, period);

        return ResponseEntity.ok(ApiResponse.<EnergySummaryResponse>builder()
                .code(1000)
                .result(full.getSummary())
                .build());
    }

    @GetMapping("/devices/{deviceId}/energy")
    public ResponseEntity<ApiResponse<DeviceEnergyBreakdown>> getDeviceEnergy(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID deviceId,
            @RequestParam(required = false, defaultValue = "30D") String period) {

        DeviceEnergyBreakdown result = energyAnalyticsService.getDeviceEnergy(userDetails.getId(), deviceId, period);

        return ResponseEntity.ok(ApiResponse.<DeviceEnergyBreakdown>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @GetMapping("/homes/{homeId}/dr/settings")
    public ResponseEntity<ApiResponse<com.hesta.backend.energy.demandresponse.DemandResponseSettings>> getDemandResponseSettings(
            @PathVariable UUID homeId) {
        if (demandResponseService == null) {
            return ResponseEntity.ok(ApiResponse.<com.hesta.backend.energy.demandresponse.DemandResponseSettings>builder().build());
        }
        com.hesta.backend.energy.demandresponse.DemandResponseSettings settings = demandResponseService.getSettings(homeId);
        return ResponseEntity.ok(ApiResponse.<com.hesta.backend.energy.demandresponse.DemandResponseSettings>builder()
                .code(1000)
                .result(settings)
                .build());
    }

    @PutMapping("/homes/{homeId}/dr/settings")
    public ResponseEntity<ApiResponse<com.hesta.backend.energy.demandresponse.DemandResponseSettings>> updateDemandResponseSettings(
            @PathVariable UUID homeId,
            @RequestBody com.hesta.backend.energy.demandresponse.DemandResponseSettings settings) {
        if (demandResponseService == null) {
            return ResponseEntity.ok(ApiResponse.<com.hesta.backend.energy.demandresponse.DemandResponseSettings>builder().build());
        }
        com.hesta.backend.energy.demandresponse.DemandResponseSettings updated = demandResponseService.updateSettings(homeId, settings);
        return ResponseEntity.ok(ApiResponse.<com.hesta.backend.energy.demandresponse.DemandResponseSettings>builder()
                .code(1000)
                .message("Đã cập nhật cài đặt Demand Response & Peak Shaving")
                .result(updated)
                .build());
    }

    @PostMapping("/homes/{homeId}/dr/evaluate")
    public ResponseEntity<ApiResponse<com.hesta.backend.energy.demandresponse.DemandResponseEvaluationResult>> evaluateDemandResponse(
            @PathVariable UUID homeId,
            @RequestParam(required = false) Double simulatedWatts) {
        if (demandResponseService == null) {
            return ResponseEntity.ok(ApiResponse.<com.hesta.backend.energy.demandresponse.DemandResponseEvaluationResult>builder().build());
        }
        com.hesta.backend.energy.demandresponse.DemandResponseEvaluationResult result = demandResponseService.evaluateAndShed(homeId, simulatedWatts);
        return ResponseEntity.ok(ApiResponse.<com.hesta.backend.energy.demandresponse.DemandResponseEvaluationResult>builder()
                .code(1000)
                .message(result.getMessage())
                .result(result)
                .build());
    }
}
