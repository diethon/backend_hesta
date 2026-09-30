package com.hesta.backend.repository;

import com.hesta.backend.entity.Device;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {

    @Query("""
            select device.room.home.id as homeId, device.id as deviceId, device.room.id as roomId,
                   null as metricType, null as referenceTime
            from Device device where device.isDeleted = false
            """)
    List<TwinHealthReference> findHealthReferences();

    @Query("""
            select device.room.home.id as homeId, device.id as deviceId, device.room.id as roomId,
                   null as metricType, null as referenceTime
            from Device device where device.id = :id and device.isDeleted = false
            """)
    Optional<TwinHealthReference> findHealthReference(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select device from Device device where device.id = :id and device.isDeleted = false")
    Optional<Device> findForSensorReading(@Param("id") UUID id);

    @Query("SELECT d FROM Device d WHERE d.room.home.id = :homeId")
    List<Device> findByHomeId(@Param("homeId") UUID homeId);

    @EntityGraph(attributePaths = "room")
    @Query("SELECT d FROM Device d WHERE d.room.home.id = :homeId ORDER BY d.id ASC")
    List<Device> findByHomeIdOrderByIdAsc(@Param("homeId") UUID homeId);

    List<Device> findByRoomId(UUID roomId);

    @Query("SELECT d FROM Device d WHERE d.node.nodeCode = :nodeCode AND d.name = :name")
    Optional<Device> findByNodeCodeAndName(@Param("nodeCode") String nodeCode, @Param("name") String name);

    @Query("SELECT d FROM Device d WHERE d.node.nodeCode = :nodeCode AND d.localId = :localId")
    Optional<Device> findByNodeCodeAndLocalId(@Param("nodeCode") String nodeCode, @Param("localId") String localId);

    Optional<Device> findByMqttTopic(String mqttTopic);

    @Query("SELECT d FROM Device d WHERE d.room.home.id = :homeId ORDER BY d.name ASC")
    List<Device> findAllByHomeIdOrderByNameAsc(@Param("homeId") UUID homeId);

    List<Device> findAllByRoom_Home_IdOrderByNameAsc(UUID homeId);

    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM Device d JOIN HomeMember hm ON d.room.home.id = hm.home.id WHERE d.id = :deviceId AND hm.user.id = :userId")
    boolean hasAccessToDevice(@Param("deviceId") UUID deviceId, @Param("userId") UUID userId);

}
