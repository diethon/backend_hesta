package com.hesta.backend.repository;

import com.hesta.backend.entity.Device;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findByHomeId(UUID homeId);
    @EntityGraph(attributePaths = "room")
    List<Device> findByHomeIdOrderByIdAsc(UUID homeId);
    List<Device> findByRoomId(UUID roomId);
    Optional<Device> findByNodeIdAndGpioPin(UUID nodeId, Short gpioPin);
    List<Device> findAllByHomeIdOrderByNameAsc(UUID homeId);
}
