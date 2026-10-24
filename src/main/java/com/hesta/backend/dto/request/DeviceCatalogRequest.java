package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceCatalogRequest {

    @NotBlank(message = "Tên mẫu thiết bị không được để trống")
    private String name;

    @NotBlank(message = "Loại thiết bị không được để trống")
    private String deviceType;

    @NotBlank(message = "Mã model không được để trống")
    private String modelCode;

    @NotBlank(message = "Tiền tố số sê-ri không được để trống")
    private String serialPrefix;

    private String description;

    private String imageUrl;

    private String icon;

    private Map<String, List<String>> defaultCapabilities;
}
