package com.hesta.backend.repository;

import com.hesta.backend.entity.SensorReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {
    String HEALTH_REFERENCES = """
            select device.home.id as homeId, device.id as deviceId, device.room.id as roomId,
                   reading.metricType as metricType, reading.recordedAt as referenceTime
            from SensorReading reading join reading.device device
            where device.isDeleted = false and not exists (
                select newer.id from SensorReading newer
                where newer.device.id = device.id and newer.metricType = reading.metricType
                  and (newer.recordedAt > reading.recordedAt
                    or (newer.recordedAt = reading.recordedAt and newer.id > reading.id))
            )
            """;

    @Query(HEALTH_REFERENCES)
    List<TwinHealthReference> findHealthReferences();

    @Query(HEALTH_REFERENCES + " and device.id = :deviceId and reading.metricType = :metricType")
    Optional<TwinHealthReference> findHealthReference(@Param("deviceId") UUID deviceId,
                                                       @Param("metricType") String metricType);

    Optional<SensorReading> findFirstByDeviceIdAndMetricTypeOrderByRecordedAtDescIdDesc(
            UUID deviceId, String metricType);

    @Query("""
            select reading from SensorReading reading
            join fetch reading.device device
            left join fetch device.room
            where device.home.id = :homeId and device.isDeleted = false
              and not exists (
                select newer.id from SensorReading newer
                where newer.device.id = device.id and newer.metricType = reading.metricType
                  and (newer.recordedAt > reading.recordedAt
                    or (newer.recordedAt = reading.recordedAt and newer.id > reading.id))
              )
            order by device.id, reading.metricType
            """)
    List<SensorReading> findLatestByHomeId(@Param("homeId") UUID homeId);

    List<SensorReading> findByDeviceIdOrderByRecordedAtDesc(UUID deviceId);
}
