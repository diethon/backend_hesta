package com.hesta.backend.repository;

import com.hesta.backend.entity.DeviceEnergyRollup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceEnergyRollupRepository extends JpaRepository<DeviceEnergyRollup, UUID> {

    List<DeviceEnergyRollup> findByDeviceIdInAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
            Collection<UUID> deviceIds,
            OffsetDateTime from,
            OffsetDateTime toExclusive
    );

    List<DeviceEnergyRollup> findByDeviceIdAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
            UUID deviceId,
            OffsetDateTime from,
            OffsetDateTime toExclusive
    );

    Optional<DeviceEnergyRollup> findByDeviceIdAndBucketStart(UUID deviceId, OffsetDateTime bucketStart);

    @Query("SELECT COALESCE(SUM(r.kwh), 0) FROM DeviceEnergyRollup r WHERE r.deviceId IN :deviceIds AND r.bucketStart >= :from AND r.bucketStart < :toExclusive")
    BigDecimal sumKwhByDeviceIdsAndWindow(
            @Param("deviceIds") Collection<UUID> deviceIds,
            @Param("from") OffsetDateTime from,
            @Param("toExclusive") OffsetDateTime toExclusive
    );
}
