package com.hesta.backend.repository;

import com.hesta.backend.entity.AutomationSchedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public interface AutomationScheduleRepository extends JpaRepository<AutomationSchedule, UUID> {
    List<AutomationSchedule> findAllByRuleIdOrderByScheduledTimeAsc(UUID ruleId);

    long countByRuleIdAndActiveTrue(UUID ruleId);

    @org.springframework.data.jpa.repository.Query("select distinct s from AutomationSchedule s join s.rule r join r.actions a "
            + "where r.home.id = :homeId and r.enabled = true and s.active = true and a.device.id = :deviceId and upper(a.action) = upper(:action)")
    List<AutomationSchedule> findActiveForDeviceAction(@org.springframework.data.repository.query.Param("homeId") UUID homeId,
                                                   @org.springframework.data.repository.query.Param("deviceId") UUID deviceId,
                                                   @org.springframework.data.repository.query.Param("action") String action);

    @EntityGraph(attributePaths = {"rule", "rule.home"})
    List<AutomationSchedule> findAllByActiveTrueAndScheduledTime(LocalTime scheduledTime);
}
