package com.hesta.backend.repository;

import com.hesta.backend.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {

    @Query("SELECT d FROM Device d WHERE d.room.home.id = :homeId")
    List<Device> findByHomeId(@Param("homeId") UUID homeId);

    @Query("SELECT d FROM Device d WHERE d.room.id = :roomId")
    List<Device> findByRoomId(@Param("roomId") UUID roomId);

    @Query("SELECT d FROM Device d JOIN FETCH d.room r JOIN FETCH r.home WHERE d.id = :deviceId")
    Optional<Device> findByIdWithRoomHome(@Param("deviceId") UUID deviceId);

    @Query("SELECT d FROM Device d WHERE d.node.nodeCode = :nodeCode AND d.name = :name")
    Optional<Device> findByNodeCodeAndName(@Param("nodeCode") String nodeCode, @Param("name") String name);

    Optional<Device> findByMqttTopic(String mqttTopic);
    List<Device> findAllByRoom_Home_IdOrderByNameAsc(UUID homeId);

    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM Device d JOIN HomeMember hm ON d.room.home.id = hm.home.id WHERE d.id = :deviceId AND hm.user.id = :userId")
    boolean hasAccessToDevice(@Param("deviceId") UUID deviceId, @Param("userId") UUID userId);
}
