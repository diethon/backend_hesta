package com.hesta.backend.repository;

import com.hesta.backend.entity.TwinLayout;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TwinLayoutRepository extends JpaRepository<TwinLayout, UUID> {
    Optional<TwinLayout> findByHomeId(UUID homeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select layout from TwinLayout layout where layout.homeId = :homeId")
    Optional<TwinLayout> findByHomeIdForUpdate(@Param("homeId") UUID homeId);
}
