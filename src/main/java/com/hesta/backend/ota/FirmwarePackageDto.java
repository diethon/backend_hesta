package com.hesta.backend.ota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FirmwarePackageDto {
    private UUID id;
    private String deviceType;
    private String version;
    private String fileUrl;
    private String checksumSha256;
    private long fileSizeBytes;
    private String releaseNotes;
    private OffsetDateTime createdAt;
}
