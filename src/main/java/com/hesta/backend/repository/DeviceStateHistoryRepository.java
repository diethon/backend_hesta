package com.hesta.backend.repository;

import com.hesta.backend.entity.DeviceStateHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeviceStateHistoryRepository extends JpaRepository<DeviceStateHistory, UUID> {
    List<DeviceStateHistory> findByDeviceIdOrderByChangedAtDesc(UUID deviceId);
}
