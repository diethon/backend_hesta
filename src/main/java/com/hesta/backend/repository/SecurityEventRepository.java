package com.hesta.backend.repository;

import com.hesta.backend.entity.SecurityEvent;
import com.hesta.backend.enums.SecurityEventStatus;
import com.hesta.backend.enums.SecurityEventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SecurityEventRepository extends JpaRepository<SecurityEvent, UUID> {

    @Query(value = """
        SELECT e FROM SecurityEvent e
        LEFT JOIN FETCH e.device d
        LEFT JOIN FETCH d.room
        WHERE e.home.id = :homeId
          AND (:status IS NULL OR e.status = :status)
          AND (:eventType IS NULL OR e.eventType = :eventType)
        ORDER BY e.detectedAt DESC
    """, countQuery = """
        SELECT count(e) FROM SecurityEvent e
        WHERE e.home.id = :homeId
          AND (:status IS NULL OR e.status = :status)
          AND (:eventType IS NULL OR e.eventType = :eventType)
    """)
    Page<SecurityEvent> findByHomeIdWithFilters(
            @Param("homeId") UUID homeId,
            @Param("status") SecurityEventStatus status,
            @Param("eventType") SecurityEventType eventType,
            Pageable pageable
    );

    @Query(value = """
        SELECT e FROM SecurityEvent e
        JOIN FETCH e.device d
        LEFT JOIN FETCH d.room
        WHERE e.home.id = :homeId
          AND d.id IN :deviceIds
          AND (:status IS NULL OR e.status = :status)
          AND (:eventType IS NULL OR e.eventType = :eventType)
        ORDER BY e.detectedAt DESC
    """, countQuery = """
        SELECT count(e) FROM SecurityEvent e
        WHERE e.home.id = :homeId
          AND e.device.id IN :deviceIds
          AND (:status IS NULL OR e.status = :status)
          AND (:eventType IS NULL OR e.eventType = :eventType)
    """)
    Page<SecurityEvent> findByHomeIdAndDeviceIdsWithFilters(
            @Param("homeId") UUID homeId,
            @Param("deviceIds") Collection<UUID> deviceIds,
            @Param("status") SecurityEventStatus status,
            @Param("eventType") SecurityEventType eventType,
            Pageable pageable
    );

    Optional<SecurityEvent> findByIdAndHomeId(UUID id, UUID homeId);

    boolean existsByDeviceIdAndDetectedAtAfter(UUID deviceId, OffsetDateTime detectedAt);
}
