package com.hesta.backend.repository;

import com.hesta.backend.entity.Room;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoomRepository extends JpaRepository<Room, UUID> {
    List<Room> findByHomeId(UUID homeId);

    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM Room r JOIN HomeMember hm ON r.home.id = hm.home.id WHERE r.id = :roomId AND hm.user.id = :userId")
    boolean hasAccessToRoom(@Param("roomId") UUID roomId, @Param("userId") UUID userId);
}
