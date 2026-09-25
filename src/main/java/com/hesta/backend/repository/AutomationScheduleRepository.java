package com.hesta.backend.repository;

import com.hesta.backend.entity.AutomationSchedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public interface AutomationScheduleRepository extends JpaRepository<AutomationSchedule, UUID> {
    List<AutomationSchedule> findAllByRuleIdOrderByScheduledTimeAsc(UUID ruleId);

    @EntityGraph(attributePaths = {"rule", "rule.home"})
    List<AutomationSchedule> findAllByActiveTrueAndScheduledTime(LocalTime scheduledTime);
}
