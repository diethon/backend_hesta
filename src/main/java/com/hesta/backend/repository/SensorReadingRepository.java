package com.hesta.backend.repository;

import com.hesta.backend.entity.SensorReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {
    List<SensorReading> findByDeviceIdOrderByRecordedAtDesc(UUID deviceId);
}
