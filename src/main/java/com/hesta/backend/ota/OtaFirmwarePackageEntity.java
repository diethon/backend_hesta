package com.hesta.backend.ota;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ota_firmware_packages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtaFirmwarePackageEntity {

    @Id
    private UUID id;

    @Column(name = "device_type", nullable = false, length = 50)
    private String deviceType;

    @Column(name = "version", nullable = false, length = 50)
    private String version;

    @Column(name = "file_url", nullable = false, columnDefinition = "text")
    private String fileUrl;

    @Column(name = "storage_key", length = 255)
    private String storageKey;

    @Column(name = "original_file_name", length = 255)
    private String originalFileName;

    @Column(name = "is_uploaded", nullable = false)
    @Builder.Default
    private boolean uploaded = false;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "release_notes", columnDefinition = "text")
    private String releaseNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
