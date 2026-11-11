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

    List<DeviceStateHistory> findByDeviceIdInOrderByChangedAtDesc(List<UUID> deviceIds);

    List<DeviceStateHistory> findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(
            UUID deviceId, OffsetDateTime from, OffsetDateTime to);

    java.util.Optional<DeviceStateHistory> findFirstByDeviceIdAndChangedAtLessThanOrderByChangedAtDesc(
            UUID deviceId, OffsetDateTime beforeTime);

    @Query("SELECT h FROM DeviceStateHistory h WHERE h.device.id IN :deviceIds AND h.changedAt BETWEEN :from AND :to ORDER BY h.changedAt ASC")
    List<DeviceStateHistory> findByDeviceIdsAndChangedAtBetween(
            @Param("deviceIds") List<UUID> deviceIds,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    @Modifying
    @Transactional
    @Query("DELETE FROM DeviceStateHistory d WHERE d.changedAt < :cutoffDate")
    int deleteOlderThan(@Param("cutoffDate") OffsetDateTime cutoffDate);
}
