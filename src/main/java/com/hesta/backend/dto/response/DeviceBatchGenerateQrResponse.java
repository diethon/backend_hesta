package com.hesta.backend.dto.response;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceBatchGenerateQrResponse {

    private DeviceCatalogResponse catalog;
    private int generatedCount;
    private List<AdminDeviceCreatedResponse> items;
}
