package com.hesta.backend.repository;

import com.hesta.backend.entity.DeviceCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceCatalogRepository extends JpaRepository<DeviceCatalog, UUID> {

    Optional<DeviceCatalog> findByModelCode(String modelCode);

    boolean existsByModelCode(String modelCode);

    List<DeviceCatalog> findAllByIsActiveTrueOrderByCreatedAtDesc();

    @Query("""
        SELECT c FROM DeviceCatalog c
        WHERE (:deviceType IS NULL OR c.deviceType = :deviceType)
          AND (:namePattern IS NULL OR LOWER(c.name) LIKE :namePattern OR LOWER(c.modelCode) LIKE :codePattern)
          AND c.isActive = true
        ORDER BY c.createdAt DESC
    """)
    List<DeviceCatalog> searchCatalogs(
            @Param("namePattern") String namePattern,
            @Param("codePattern") String codePattern,
            @Param("deviceType") String deviceType
    );
}
