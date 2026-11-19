package com.hesta.backend.repository;

import com.hesta.backend.entity.ParentalGuardianRule;
import com.hesta.backend.enums.GuardianStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ParentalGuardianRuleRepository extends JpaRepository<ParentalGuardianRule, UUID> {
    List<ParentalGuardianRule> findAllByHomeIdOrderByCreatedAtDesc(UUID homeId);

    Optional<ParentalGuardianRule> findByIdAndHomeId(UUID id, UUID homeId);

    List<ParentalGuardianRule> findAllByTargetDeviceIdAndEnabledTrue(UUID targetDeviceId);

    List<ParentalGuardianRule> findAllByCameraDeviceIdAndEnabledTrue(UUID cameraDeviceId);

    List<ParentalGuardianRule> findAllByEnabledTrueAndStatusIn(List<GuardianStatus> statuses);
}
