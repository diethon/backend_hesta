package com.hesta.backend.controller;

import com.hesta.backend.dto.request.DeviceBatchGenerateQrRequest;
import com.hesta.backend.dto.request.DeviceCatalogRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.DeviceAdminStatsResponse;
import com.hesta.backend.dto.response.DeviceBatchGenerateQrResponse;
import com.hesta.backend.dto.response.DeviceCatalogResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.DeviceCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/device-catalogs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDeviceCatalogController {

    private final DeviceCatalogService catalogService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DeviceCatalogResponse>>> getCatalogs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String deviceType) {

        List<DeviceCatalogResponse> result = catalogService.getCatalogs(keyword, deviceType);
        ApiResponse<List<DeviceCatalogResponse>> response = ApiResponse.<List<DeviceCatalogResponse>>builder()
                .code(1000)
                .message("Lấy danh mục sản phẩm thành công")
                .result(result)
                .build();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<DeviceAdminStatsResponse>> getStats() {
        DeviceAdminStatsResponse stats = catalogService.getDeviceAdminStats();
        ApiResponse<DeviceAdminStatsResponse> response = ApiResponse.<DeviceAdminStatsResponse>builder()
                .code(1000)
                .result(stats)
                .build();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceCatalogResponse>> getCatalog(@PathVariable UUID id) {
        DeviceCatalogResponse result = catalogService.getCatalogById(id);
        ApiResponse<DeviceCatalogResponse> response = ApiResponse.<DeviceCatalogResponse>builder()
                .code(1000)
                .result(result)
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DeviceCatalogResponse>> createCatalog(
            @Valid @RequestBody DeviceCatalogRequest request) {

        DeviceCatalogResponse result = catalogService.createCatalog(request);
        ApiResponse<DeviceCatalogResponse> response = ApiResponse.<DeviceCatalogResponse>builder()
                .code(1000)
                .message("Tạo mẫu sản phẩm thành công")
                .result(result)
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceCatalogResponse>> updateCatalog(
            @PathVariable UUID id,
            @Valid @RequestBody DeviceCatalogRequest request) {

        DeviceCatalogResponse result = catalogService.updateCatalog(id, request);
        ApiResponse<DeviceCatalogResponse> response = ApiResponse.<DeviceCatalogResponse>builder()
                .code(1000)
                .message("Cập nhật mẫu sản phẩm thành công")
                .result(result)
                .build();
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCatalog(@PathVariable UUID id) {
        catalogService.deleteCatalog(id);
        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .code(1000)
                .message("Xóa mẫu sản phẩm thành công")
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/batch-generate")
    public ResponseEntity<ApiResponse<DeviceBatchGenerateQrResponse>> batchGenerateQr(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody DeviceBatchGenerateQrRequest request) {

        DeviceBatchGenerateQrResponse result = catalogService.batchGenerateQr(userDetails.getId(), request);
        ApiResponse<DeviceBatchGenerateQrResponse> response = ApiResponse.<DeviceBatchGenerateQrResponse>builder()
                .code(1000)
                .message("Sinh " + result.getGeneratedCount() + " mã QR thiết bị thành công")
                .result(result)
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/upload-image")
    public ResponseEntity<ApiResponse<String>> uploadImage(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {

        String imageUrl = catalogService.uploadCatalogImage(file);
        ApiResponse<String> response = ApiResponse.<String>builder()
                .code(1000)
                .message("Tải ảnh mẫu thiết bị thành công")
                .result(imageUrl)
                .build();
        return ResponseEntity.ok(response);
    }
}
