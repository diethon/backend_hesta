package com.hesta.backend.repository;

import com.hesta.backend.entity.SensorReading;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Read-only access to existing telemetry. Ties use the persisted reading ID. */
public interface SensorReadingRepository extends Repository<SensorReading, Long> {
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
}
