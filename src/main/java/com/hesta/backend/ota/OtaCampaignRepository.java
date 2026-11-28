package com.hesta.backend.ota;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OtaCampaignRepository extends JpaRepository<OtaCampaignEntity, UUID> {

    List<OtaCampaignEntity> findAllByOrderByCreatedAtDesc();

    boolean existsByFirmwarePackageId(UUID firmwarePackageId);

    @Query("""
            SELECT COUNT(c) > 0 FROM OtaCampaignEntity c
            WHERE LOWER(c.deviceType) = LOWER(:deviceType)
              AND (c.status LIKE 'RUNNING%' OR c.status = 'DRAFT')
            """)
    boolean existsActiveCampaignForDeviceType(@Param("deviceType") String deviceType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM OtaCampaignEntity c WHERE c.id = :id")
    Optional<OtaCampaignEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM OtaCampaignEntity c
            WHERE c.status LIKE 'RUNNING%'
              AND LOWER(c.version) = LOWER(:version)
              AND EXISTS (
                  SELECT 1 FROM OtaCampaignNodeEntity cn
                  WHERE cn.campaignId = c.id
                    AND (LOWER(cn.nodeCode) = LOWER(:nodeCode)
                         OR (:nodeId IS NOT NULL AND cn.nodeId = :nodeId))
              )
            ORDER BY c.createdAt DESC
            """)
    List<OtaCampaignEntity> findActiveCampaignsForNodeAndVersionForUpdate(
            @Param("nodeCode") String nodeCode,
            @Param("nodeId") UUID nodeId,
            @Param("version") String version
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM OtaCampaignEntity c
            WHERE c.status LIKE 'RUNNING%'
              AND EXISTS (
                  SELECT 1 FROM OtaCampaignNodeEntity cn
                  WHERE cn.campaignId = c.id
                    AND (LOWER(cn.nodeCode) = LOWER(:nodeCode)
                         OR (:nodeId IS NOT NULL AND cn.nodeId = :nodeId))
              )
            ORDER BY c.createdAt DESC
            """)
    List<OtaCampaignEntity> findRunningCampaignsForNodeForUpdate(
            @Param("nodeCode") String nodeCode,
            @Param("nodeId") UUID nodeId
    );
}
