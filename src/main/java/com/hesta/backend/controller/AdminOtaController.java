package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.ota.CreateOtaCampaignRequest;
import com.hesta.backend.ota.FirmwarePackageDto;
import com.hesta.backend.ota.OtaCampaignDto;
import com.hesta.backend.ota.OtaCampaignService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ota")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminOtaController {

    private final OtaCampaignService otaCampaignService;

    @PostMapping("/packages")
    public ResponseEntity<ApiResponse<FirmwarePackageDto>> registerPackage(@RequestBody FirmwarePackageDto dto) {
        FirmwarePackageDto result = otaCampaignService.registerPackage(dto);
        return ResponseEntity.ok(ApiResponse.<FirmwarePackageDto>builder()
                .code(1000)
                .message("Đăng ký Firmware Package thành công")
                .result(result)
                .build());
    }

    @GetMapping("/packages")
    public ResponseEntity<ApiResponse<List<FirmwarePackageDto>>> listPackages() {
        List<FirmwarePackageDto> packages = otaCampaignService.listPackages();
        return ResponseEntity.ok(ApiResponse.<List<FirmwarePackageDto>>builder()
                .code(1000)
                .result(packages)
                .build());
    }

    @GetMapping("/packages/{id}")
    public ResponseEntity<ApiResponse<FirmwarePackageDto>> getPackage(@PathVariable UUID id) {
        FirmwarePackageDto pkg = otaCampaignService.getPackage(id);
        return ResponseEntity.ok(ApiResponse.<FirmwarePackageDto>builder()
                .code(1000)
                .result(pkg)
                .build());
    }

    @PostMapping("/campaigns")
    public ResponseEntity<ApiResponse<OtaCampaignDto>> createAndStartCampaign(@RequestBody CreateOtaCampaignRequest request) {
        OtaCampaignDto campaign = otaCampaignService.createAndStartCampaign(request);
        return ResponseEntity.ok(ApiResponse.<OtaCampaignDto>builder()
                .code(1000)
                .message("Đã khởi tạo và bắt đầu chiến dịch OTA Firmware Rollout")
                .result(campaign)
                .build());
    }

    @GetMapping("/campaigns")
    public ResponseEntity<ApiResponse<List<OtaCampaignDto>>> listCampaigns() {
        List<OtaCampaignDto> campaigns = otaCampaignService.listCampaigns();
        return ResponseEntity.ok(ApiResponse.<List<OtaCampaignDto>>builder()
                .code(1000)
                .result(campaigns)
                .build());
    }

    @GetMapping("/campaigns/{id}")
    public ResponseEntity<ApiResponse<OtaCampaignDto>> getCampaign(@PathVariable UUID id) {
        OtaCampaignDto campaign = otaCampaignService.getCampaign(id);
        return ResponseEntity.ok(ApiResponse.<OtaCampaignDto>builder()
                .code(1000)
                .result(campaign)
                .build());
    }

    @PostMapping("/campaigns/{id}/abort")
    public ResponseEntity<ApiResponse<OtaCampaignDto>> abortCampaign(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "Admin hủy khẩn cấp") String reason) {
        OtaCampaignDto campaign = otaCampaignService.abortCampaign(id, reason);
        return ResponseEntity.ok(ApiResponse.<OtaCampaignDto>builder()
                .code(1000)
                .message("Đã hủy khẩn cấp chiến dịch OTA Firmware thành công")
                .result(campaign)
                .build());
    }
}
