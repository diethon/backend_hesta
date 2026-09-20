package com.hesta.backend.repository;

import com.hesta.backend.entity.Device;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findByHomeId(UUID homeId);
    List<Device> findByRoomId(UUID roomId);
    Optional<Device> findByNodeIdAndGpioPin(UUID nodeId, Short gpioPin);
    List<Device> findAllByHomeIdOrderByNameAsc(UUID homeId);

    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM Device d JOIN HomeMember hm ON d.home.id = hm.home.id WHERE d.id = :deviceId AND hm.user.id = :userId")
    boolean hasAccessToDevice(@Param("deviceId") UUID deviceId, @Param("userId") UUID userId);
}
