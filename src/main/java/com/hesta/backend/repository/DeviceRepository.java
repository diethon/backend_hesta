package com.hesta.backend.repository;

import com.hesta.backend.entity.Device;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {
    // Serializes latest-state decisions for concurrent submissions on one device.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select device from Device device where device.id = :id and device.isDeleted = false")
    Optional<Device> findForSensorReading(@Param("id") UUID id);

    List<Device> findByHomeId(UUID homeId);
    @EntityGraph(attributePaths = "room")
    List<Device> findByHomeIdOrderByIdAsc(UUID homeId);
    List<Device> findByRoomId(UUID roomId);
    Optional<Device> findByNodeIdAndGpioPin(UUID nodeId, Short gpioPin);
    List<Device> findAllByHomeIdOrderByNameAsc(UUID homeId);
}
