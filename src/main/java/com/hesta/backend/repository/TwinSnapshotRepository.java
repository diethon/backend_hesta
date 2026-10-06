package com.hesta.backend.repository;

import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.SensorReading;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Read queries used only by the Digital Twin snapshot. */
public interface TwinSnapshotRepository extends Repository<Device, UUID> {
    @Query("""
            select device from Device device
            left join fetch device.room room
            left join fetch device.node node
            where (room.home.id = :homeId or (device.room is null and node.home.id = :homeId))
              and device.isDeleted = false
            order by device.id
            """)
    List<Device> findDevicesByHomeId(@Param("homeId") UUID homeId);

    @Query("""
            select reading from SensorReading reading
            join fetch reading.device device
            left join fetch device.room room
            left join fetch device.node node
            where (room.home.id = :homeId or (device.room is null and node.home.id = :homeId))
              and device.isDeleted = false
              and not exists (
                select newer.id from SensorReading newer
                where newer.device.id = device.id and newer.metricType = reading.metricType
                  and (newer.recordedAt > reading.recordedAt
                    or (newer.recordedAt = reading.recordedAt and newer.id > reading.id))
              )
            order by device.id, reading.metricType
            """)
    List<SensorReading> findLatestReadingsByHomeId(@Param("homeId") UUID homeId);
}
