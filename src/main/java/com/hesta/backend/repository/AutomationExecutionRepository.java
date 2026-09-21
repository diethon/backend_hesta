package com.hesta.backend.repository;

import com.hesta.backend.entity.AutomationExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AutomationExecutionRepository extends JpaRepository<AutomationExecution, UUID> {
    List<AutomationExecution> findTop100ByRuleIdOrderByMatchedAtDesc(UUID ruleId);
}
