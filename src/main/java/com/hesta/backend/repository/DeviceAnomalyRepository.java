package com.hesta.backend.repository;

import com.hesta.backend.entity.DeviceAnomaly;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.AnomalyType;
import com.hesta.backend.enums.SeverityLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceAnomalyRepository extends JpaRepository<DeviceAnomaly, UUID> {

    Optional<DeviceAnomaly> findByDeviceIdAndAnomalyTypeAndStatusIn(
            UUID deviceId,
            AnomalyType anomalyType,
            Collection<AnomalyStatus> statuses
    );

    List<DeviceAnomaly> findByDeviceIdAndStatusIn(
            UUID deviceId,
            Collection<AnomalyStatus> statuses
    );

    List<DeviceAnomaly> findByAnomalyTypeAndStatusIn(
            AnomalyType anomalyType,
            Collection<AnomalyStatus> statuses
    );

    Optional<DeviceAnomaly> findByIdAndHomeId(UUID id, UUID homeId);

    @Query(value = """
        SELECT a FROM DeviceAnomaly a
        JOIN FETCH a.device d
        WHERE a.home.id = :homeId
          AND (:status IS NULL OR a.status = :status)
          AND (:severity IS NULL OR a.severity = :severity)
          AND d.isDeleted = false
        ORDER BY a.detectedAt DESC
    """, countQuery = """
        SELECT count(a) FROM DeviceAnomaly a
        JOIN a.device d
        WHERE a.home.id = :homeId
          AND (:status IS NULL OR a.status = :status)
          AND (:severity IS NULL OR a.severity = :severity)
          AND d.isDeleted = false
    """)
    Page<DeviceAnomaly> findByHomeIdWithFilters(
            @Param("homeId") UUID homeId,
            @Param("status") AnomalyStatus status,
            @Param("severity") SeverityLevel severity,
            Pageable pageable
    );

    @Query(value = """
        SELECT a FROM DeviceAnomaly a
        JOIN FETCH a.device d
        WHERE a.home.id = :homeId
          AND a.device.id IN :deviceIds
          AND (:status IS NULL OR a.status = :status)
          AND (:severity IS NULL OR a.severity = :severity)
          AND d.isDeleted = false
        ORDER BY a.detectedAt DESC
    """, countQuery = """
        SELECT count(a) FROM DeviceAnomaly a
        JOIN a.device d
        WHERE a.home.id = :homeId
          AND a.device.id IN :deviceIds
          AND (:status IS NULL OR a.status = :status)
          AND (:severity IS NULL OR a.severity = :severity)
          AND d.isDeleted = false
    """)
    Page<DeviceAnomaly> findByHomeIdAndDeviceIdsWithFilters(
            @Param("homeId") UUID homeId,
            @Param("deviceIds") Collection<UUID> deviceIds,
            @Param("status") AnomalyStatus status,
            @Param("severity") SeverityLevel severity,
            Pageable pageable
    );
}
