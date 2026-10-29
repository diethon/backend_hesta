package com.hesta.backend.repository;

import com.hesta.backend.entity.DeviceRegistrationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRegistrationTokenRepository extends JpaRepository<DeviceRegistrationToken, UUID> {

    @Query("SELECT t FROM DeviceRegistrationToken t JOIN FETCH t.device d WHERE t.tokenHash = :tokenHash")
    Optional<DeviceRegistrationToken> findByTokenHashWithDevice(@Param("tokenHash") String tokenHash);

    Optional<DeviceRegistrationToken> findByTokenHash(String tokenHash);

    List<DeviceRegistrationToken> findByDeviceIdAndRevokedAtIsNullAndUsedAtIsNull(UUID deviceId);

    @Query("SELECT t FROM DeviceRegistrationToken t WHERE t.device.id = :deviceId AND t.revokedAt IS NULL AND t.usedAt IS NULL ORDER BY t.createdAt DESC")
    List<DeviceRegistrationToken> findActiveTokensByDeviceId(@Param("deviceId") UUID deviceId);
}
