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
    @Query("SELECT h FROM DeviceStateHistory h WHERE h.device.id = :deviceId ORDER BY h.changedAt DESC LIMIT 200")
    List<DeviceStateHistory> findByDeviceIdOrderByChangedAtDesc(@Param("deviceId") UUID deviceId);

    @Query("SELECT h FROM DeviceStateHistory h WHERE h.device.id IN :deviceIds ORDER BY h.changedAt DESC LIMIT 500")
    List<DeviceStateHistory> findByDeviceIdInOrderByChangedAtDesc(@Param("deviceIds") List<UUID> deviceIds);

    @Query("SELECT h FROM DeviceStateHistory h WHERE h.device.id IN :deviceIds ORDER BY h.changedAt DESC")
    List<DeviceStateHistory> findRecentByDeviceIds(
            @Param("deviceIds") List<UUID> deviceIds,
            org.springframework.data.domain.Pageable pageable);

    List<DeviceStateHistory> findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(
            UUID deviceId, OffsetDateTime from, OffsetDateTime to);

    java.util.Optional<DeviceStateHistory> findFirstByDeviceIdAndChangedAtLessThanOrderByChangedAtDesc(
            UUID deviceId, OffsetDateTime beforeTime);

    long countByDeviceId(UUID deviceId);

    java.util.Optional<DeviceStateHistory> findFirstByDeviceIdOrderByChangedAtDesc(UUID deviceId);

    @Query("SELECT h FROM DeviceStateHistory h WHERE h.device.id IN :deviceIds AND h.changedAt BETWEEN :from AND :to ORDER BY h.changedAt ASC")
    List<DeviceStateHistory> findByDeviceIdsAndChangedAtBetween(
            @Param("deviceIds") List<UUID> deviceIds,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    @Query("""
            SELECT h FROM DeviceStateHistory h
            JOIN FETCH h.device
            WHERE h.device.id IN :deviceIds
              AND h.changedAt < :beforeTime
              AND NOT EXISTS (
                  SELECT h2.id FROM DeviceStateHistory h2
                  WHERE h2.device.id = h.device.id
                    AND h2.changedAt < :beforeTime
                    AND (h2.changedAt > h.changedAt
                         OR (h2.changedAt = h.changedAt AND h2.id > h.id))
              )
            """)
    List<DeviceStateHistory> findLatestBeforeByDeviceIds(
            @Param("deviceIds") List<UUID> deviceIds,
            @Param("beforeTime") OffsetDateTime beforeTime);

    @Modifying
    @Transactional
    @Query("DELETE FROM DeviceStateHistory d WHERE d.changedAt < :cutoffDate")
    int deleteOlderThan(@Param("cutoffDate") OffsetDateTime cutoffDate);
}
