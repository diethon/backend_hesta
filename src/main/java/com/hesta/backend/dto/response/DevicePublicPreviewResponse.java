package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevicePublicPreviewResponse {
    private UUID deviceId;
    private String name;
    private String deviceType;
    private String model;
    private String serialNumber;
    private String status;
}
