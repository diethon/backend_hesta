package com.hesta.backend.repository;

import com.hesta.backend.entity.ParentalGuardianLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ParentalGuardianLogRepository extends JpaRepository<ParentalGuardianLog, UUID> {
    List<ParentalGuardianLog> findAllByRuleIdOrderByRecordedAtDesc(UUID ruleId);

    List<ParentalGuardianLog> findAllByHomeIdOrderByRecordedAtDesc(UUID homeId);
}
