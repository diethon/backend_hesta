package com.hesta.backend.repository;

import com.hesta.backend.entity.Home;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface HomeRepository extends JpaRepository<Home, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select home from Home home where home.id = :id")
    Optional<Home> findByIdForUpdate(@Param("id") UUID id);
}
