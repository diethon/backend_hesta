package com.hesta.backend.repository;

import com.hesta.backend.entity.DeviceGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceGroupRepository extends JpaRepository<DeviceGroup, UUID> {

    @Query("SELECT DISTINCT g FROM DeviceGroup g LEFT JOIN FETCH g.devices WHERE g.home.id = :homeId ORDER BY g.createdAt ASC")
    List<DeviceGroup> findByHomeIdWithDevices(@Param("homeId") UUID homeId);

    @Query("SELECT g FROM DeviceGroup g LEFT JOIN FETCH g.devices WHERE g.id = :id AND g.home.id = :homeId")
    Optional<DeviceGroup> findByIdAndHomeId(@Param("id") UUID id, @Param("homeId") UUID homeId);
}
