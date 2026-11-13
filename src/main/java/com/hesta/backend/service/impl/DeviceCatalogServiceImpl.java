package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.AdminCreateDeviceRequest;
import com.hesta.backend.dto.request.DeviceBatchGenerateQrRequest;
import com.hesta.backend.dto.request.DeviceCatalogRequest;
import com.hesta.backend.dto.response.AdminDeviceCreatedResponse;
import com.hesta.backend.dto.response.DeviceAdminStatsResponse;
import com.hesta.backend.dto.response.DeviceBatchGenerateQrResponse;
import com.hesta.backend.dto.response.DeviceCatalogResponse;
import com.hesta.backend.entity.DeviceCapabilities;
import com.hesta.backend.entity.DeviceCatalog;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceCatalogRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.DeviceCatalogService;
import com.hesta.backend.service.DeviceOnboardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceCatalogServiceImpl implements DeviceCatalogService {

    private final DeviceCatalogRepository catalogRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceOnboardingService onboardingService;
    private final com.hesta.backend.service.CloudinaryService cloudinaryService;

    private static final Pattern NUMERIC_SUFFIX = Pattern.compile("(\\d+)$");

    @Override
    @Transactional(readOnly = true)
    public List<DeviceCatalogResponse> getCatalogs(String keyword, String deviceType) {
        String cleanKeyword = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        String pattern = cleanKeyword != null ? "%" + cleanKeyword.toLowerCase() + "%" : null;
        String cleanType = (deviceType != null && !deviceType.trim().isEmpty()) ? deviceType.trim().toUpperCase() : null;

        List<DeviceCatalog> catalogs = catalogRepository.searchCatalogs(pattern, pattern, cleanType);
        return catalogs.stream().map(c -> {
            long total = deviceRepository.countByCatalogId(c.getId());
            long unclaimed = deviceRepository.countByCatalogIdAndStatus(c.getId(), DeviceStatus.UNCLAIMED);
            long claimed = total - unclaimed;
            return DeviceCatalogResponse.fromEntity(c, total, unclaimed, claimed);
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceCatalogResponse getCatalogById(UUID id) {
        DeviceCatalog catalog = catalogRepository.findById(id)
                .filter(DeviceCatalog::isActive)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        long total = deviceRepository.countByCatalogId(catalog.getId());
        long unclaimed = deviceRepository.countByCatalogIdAndStatus(catalog.getId(), DeviceStatus.UNCLAIMED);
        return DeviceCatalogResponse.fromEntity(catalog, total, unclaimed, total - unclaimed);
    }

    @Override
    @Transactional
    public DeviceCatalogResponse createCatalog(DeviceCatalogRequest request) {
        String modelCode = request.getModelCode().trim().toUpperCase();
        if (catalogRepository.existsByModelCode(modelCode)) {
            throw new IllegalArgumentException("Mã Model '" + modelCode + "' đã tồn tại trong hệ thống");
        }

        String prefix = request.getSerialPrefix().trim();
        if (!prefix.endsWith("-")) {
            prefix += "-";
        }

        DeviceCatalog catalog = DeviceCatalog.builder()
                .name(request.getName().trim())
                .deviceType(request.getDeviceType().trim().toUpperCase())
                .modelCode(modelCode)
                .serialPrefix(prefix)
                .description(request.getDescription() != null ? request.getDescription().trim() : null)
                .imageUrl(request.getImageUrl() != null ? request.getImageUrl().trim() : null)
                .icon(request.getIcon() != null ? request.getIcon().trim() : null)
                .defaultCapabilities(request.getDefaultCapabilities() != null
                        ? new DeviceCapabilities(request.getDefaultCapabilities())
                        : new DeviceCapabilities())
                .isActive(true)
                .build();

        DeviceCatalog saved = catalogRepository.save(catalog);
        log.info("Admin created new device catalog [{} - {}]", saved.getModelCode(), saved.getName());
        return DeviceCatalogResponse.fromEntity(saved, 0, 0, 0);
    }

    @Override
    @Transactional
    public DeviceCatalogResponse updateCatalog(UUID id, DeviceCatalogRequest request) {
        DeviceCatalog catalog = catalogRepository.findById(id)
                .filter(DeviceCatalog::isActive)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        String newModel = request.getModelCode().trim().toUpperCase();
        if (!catalog.getModelCode().equalsIgnoreCase(newModel) && catalogRepository.existsByModelCode(newModel)) {
            throw new IllegalArgumentException("Mã Model '" + newModel + "' đã tồn tại trong hệ thống");
        }

        String prefix = request.getSerialPrefix().trim();
        if (!prefix.endsWith("-")) {
            prefix += "-";
        }

        catalog.setName(request.getName().trim());
        catalog.setDeviceType(request.getDeviceType().trim().toUpperCase());
        catalog.setModelCode(newModel);
        catalog.setSerialPrefix(prefix);
        catalog.setDescription(request.getDescription() != null ? request.getDescription().trim() : null);
        catalog.setImageUrl(request.getImageUrl() != null ? request.getImageUrl().trim() : null);
        catalog.setIcon(request.getIcon() != null ? request.getIcon().trim() : null);
        if (request.getDefaultCapabilities() != null) {
            catalog.setDefaultCapabilities(new DeviceCapabilities(request.getDefaultCapabilities()));
        }

        DeviceCatalog updated = catalogRepository.save(catalog);
        long total = deviceRepository.countByCatalogId(updated.getId());
        long unclaimed = deviceRepository.countByCatalogIdAndStatus(updated.getId(), DeviceStatus.UNCLAIMED);
        return DeviceCatalogResponse.fromEntity(updated, total, unclaimed, total - unclaimed);
    }

    @Override
    @Transactional
    public void deleteCatalog(UUID id) {
        DeviceCatalog catalog = catalogRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        long deviceCount = deviceRepository.countByCatalogId(id);
        if (deviceCount > 0) {
            // Soft delete so historical device references remain intact
            catalog.setActive(false);
            catalogRepository.save(catalog);
            log.info("Soft deleted device catalog [{}] as it has {} associated devices", id, deviceCount);
        } else {
            catalogRepository.delete(catalog);
            log.info("Hard deleted device catalog [{}] with 0 associated devices", id);
        }
    }

    @Override
    @Transactional
    public DeviceBatchGenerateQrResponse batchGenerateQr(UUID adminId, DeviceBatchGenerateQrRequest request) {
        DeviceCatalog catalog = catalogRepository.findById(request.getCatalogId())
                .filter(DeviceCatalog::isActive)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        int quantity = Math.max(1, Math.min(request.getQuantity(), 50));
        String prefix = catalog.getSerialPrefix();

        // Calculate next starting sequence number from existing serials
        List<String> existingSerials = deviceRepository.findSerialNumbersStartingWith(prefix);
        long maxSeq = 0;
        for (String s : existingSerials) {
            Matcher m = NUMERIC_SUFFIX.matcher(s);
            if (m.find()) {
                try {
                    long val = Long.parseLong(m.group(1));
                    if (val > maxSeq) {
                        maxSeq = val;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        List<AdminDeviceCreatedResponse> items = new ArrayList<>();
        long currentSeq = maxSeq + 1;

        for (int i = 0; i < quantity; i++) {
            // Find next available unique serial number
            String candidateSerial;
            do {
                candidateSerial = String.format("%s%04d", prefix, currentSeq++);
            } while (deviceRepository.existsBySerialNumber(candidateSerial));

            String deviceName;
            if (request.getCustomName() != null && !request.getCustomName().trim().isEmpty()) {
                deviceName = quantity == 1
                        ? request.getCustomName().trim()
                        : request.getCustomName().trim() + " #" + (maxSeq + i + 1);
            } else {
                deviceName = quantity == 1
                        ? catalog.getName()
                        : catalog.getName() + " #" + (maxSeq + i + 1);
            }

            AdminCreateDeviceRequest createReq = AdminCreateDeviceRequest.builder()
                    .name(deviceName)
                    .deviceType(catalog.getDeviceType())
                    .model(catalog.getModelCode())
                    .serialNumber(candidateSerial)
                    .imageUrl(catalog.getImageUrl())
                    .icon(catalog.getIcon())
                    .catalogId(catalog.getId())
                    .capabilities(catalog.getDefaultCapabilities())
                    .build();

            AdminDeviceCreatedResponse res = onboardingService.adminCreateDevice(adminId, createReq);
            items.add(res);
        }

        long total = deviceRepository.countByCatalogId(catalog.getId());
        long unclaimed = deviceRepository.countByCatalogIdAndStatus(catalog.getId(), DeviceStatus.UNCLAIMED);
        DeviceCatalogResponse catalogDto = DeviceCatalogResponse.fromEntity(catalog, total, unclaimed, total - unclaimed);

        log.info("Batch generated {} QR codes for catalog [{}] by admin [{}]", quantity, catalog.getModelCode(), adminId);

        return DeviceBatchGenerateQrResponse.builder()
                .catalog(catalogDto)
                .generatedCount(items.size())
                .items(items)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceAdminStatsResponse getDeviceAdminStats() {
        long totalCatalogs = catalogRepository.count();
        long totalDevices = deviceRepository.count();
        long unclaimedDevices = deviceRepository.countByStatus(DeviceStatus.UNCLAIMED);
        long claimedDevices = totalDevices - unclaimedDevices;

        Map<String, Long> typeBreakdown = new HashMap<>();
        List<Object[]> rows = deviceRepository.countGroupedByDeviceType();
        for (Object[] row : rows) {
            if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                typeBreakdown.put(row[0].toString(), ((Number) row[1]).longValue());
            }
        }

        return DeviceAdminStatsResponse.builder()
                .totalCatalogs(totalCatalogs)
                .totalDevices(totalDevices)
                .unclaimedDevices(unclaimedDevices)
                .claimedDevices(claimedDevices)
                .typeBreakdown(typeBreakdown)
                .build();
    }

    @Override
    public String uploadCatalogImage(org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Tệp hình ảnh không được để trống");
        }
        try {
            return cloudinaryService.uploadImage(file, "device-catalogs");
        } catch (java.io.IOException e) {
            log.error("Lỗi khi tải ảnh danh mục thiết bị lên Cloudinary", e);
            throw new AppException(ErrorCode.AVATAR_UPLOAD_FAILED);
        }
    }
}
