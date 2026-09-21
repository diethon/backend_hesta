package com.hesta.backend.repository;

import com.hesta.backend.entity.AutomationRule;
import com.hesta.backend.enums.TriggerType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AutomationRuleRepository extends JpaRepository<AutomationRule, UUID> {
    @EntityGraph(attributePaths = {"home", "conditions", "conditions.device"})
    List<AutomationRule> findAllByHomeIdOrderByNameAsc(UUID homeId);

    @EntityGraph(attributePaths = {"home", "conditions", "conditions.device"})
    Optional<AutomationRule> findByIdAndHomeId(UUID id, UUID homeId);

    @EntityGraph(attributePaths = {"home", "conditions", "conditions.device"})
    List<AutomationRule> findAllByHomeIdAndEnabledTrueAndTriggerTypeIn(UUID homeId, List<TriggerType> triggerTypes);

    // Call in the same transaction as a rule lookup to initialize actions on those managed rules.
    @EntityGraph(attributePaths = {"actions", "actions.device"})
    @Query("select distinct rule from AutomationRule rule where rule.id in :ids")
    List<AutomationRule> fetchActionsByIdIn(@Param("ids") List<UUID> ids);

    boolean existsByHomeIdAndName(UUID homeId, String name);
    boolean existsByHomeIdAndNameAndIdNot(UUID homeId, String name, UUID id);
}
