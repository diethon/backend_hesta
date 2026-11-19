package com.hesta.backend.dto.response;

import com.hesta.backend.entity.DeviceCatalog;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceCatalogResponse {

    private UUID id;
    private String name;
    private String deviceType;
    private String modelCode;
    private String serialPrefix;
    private String description;
    private String imageUrl;
    private String icon;
    private Map<String, List<String>> defaultCapabilities;
    private boolean isActive;
    private long totalDeviceCount;
    private long unclaimedCount;
    private long claimedCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public static DeviceCatalogResponse fromEntity(DeviceCatalog catalog, long total, long unclaimed, long claimed) {
        if (catalog == null) return null;
        return DeviceCatalogResponse.builder()
                .id(catalog.getId())
                .name(catalog.getName())
                .deviceType(catalog.getDeviceType())
                .modelCode(catalog.getModelCode())
                .serialPrefix(catalog.getSerialPrefix())
                .description(catalog.getDescription())
                .imageUrl(catalog.getImageUrl())
                .icon(catalog.getIcon())
                .defaultCapabilities(catalog.getDefaultCapabilities())
                .isActive(catalog.isActive())
                .totalDeviceCount(total)
                .unclaimedCount(unclaimed)
                .claimedCount(claimed)
                .createdAt(catalog.getCreatedAt())
                .updatedAt(catalog.getUpdatedAt())
                .build();
    }
}
