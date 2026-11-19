package com.hesta.backend.ota;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OtaFirmwarePackageRepository extends JpaRepository<OtaFirmwarePackageEntity, UUID> {
    List<OtaFirmwarePackageEntity> findAllByOrderByCreatedAtDesc();
}
