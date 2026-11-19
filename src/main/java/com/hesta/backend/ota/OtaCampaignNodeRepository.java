package com.hesta.backend.ota;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OtaCampaignNodeRepository extends JpaRepository<OtaCampaignNodeEntity, UUID> {

    List<OtaCampaignNodeEntity> findByCampaignIdOrderByWaveAscNodeCodeAsc(UUID campaignId);

    List<OtaCampaignNodeEntity> findByCampaignIdIn(List<UUID> campaignIds);

    Optional<OtaCampaignNodeEntity> findByCampaignIdAndNodeCode(UUID campaignId, String nodeCode);

    @Query("""
            SELECT cn FROM OtaCampaignNodeEntity cn
            JOIN OtaCampaignEntity c ON c.id = cn.campaignId
            WHERE c.status LIKE 'RUNNING%'
              AND cn.status IN ('DISPATCHED', 'DOWNLOADING', 'VERIFYING', 'FLASHING', 'REBOOTING')
              AND cn.updatedAt < :cutoff
            """)
    List<OtaCampaignNodeEntity> findStaleActiveNodes(@Param("cutoff") OffsetDateTime cutoff);
}
