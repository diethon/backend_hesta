package com.hesta.backend.service;

import com.hesta.backend.dto.request.DeviceBatchGenerateQrRequest;
import com.hesta.backend.dto.request.DeviceCatalogRequest;
import com.hesta.backend.dto.response.DeviceAdminStatsResponse;
import com.hesta.backend.dto.response.DeviceBatchGenerateQrResponse;
import com.hesta.backend.dto.response.DeviceCatalogResponse;

import java.util.List;
import java.util.UUID;

public interface DeviceCatalogService {

    List<DeviceCatalogResponse> getCatalogs(String keyword, String deviceType);

    DeviceCatalogResponse getCatalogById(UUID id);

    DeviceCatalogResponse createCatalog(DeviceCatalogRequest request);

    DeviceCatalogResponse updateCatalog(UUID id, DeviceCatalogRequest request);

    void deleteCatalog(UUID id);

    DeviceBatchGenerateQrResponse batchGenerateQr(UUID adminId, DeviceBatchGenerateQrRequest request);

    DeviceAdminStatsResponse getDeviceAdminStats();

    String uploadCatalogImage(org.springframework.web.multipart.MultipartFile file);
}
