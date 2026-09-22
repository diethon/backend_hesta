package com.hesta.backend.repository;

import com.hesta.backend.entity.DeviceStateHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface DeviceStateHistoryRepository extends JpaRepository<DeviceStateHistory, UUID> {
    List<DeviceStateHistory> findByDeviceIdOrderByChangedAtDesc(UUID deviceId);

    @Modifying
    @Transactional
    @Query("DELETE FROM DeviceStateHistory d WHERE d.changedAt < :cutoffDate")
    int deleteOlderThan(@Param("cutoffDate") OffsetDateTime cutoffDate);
}
