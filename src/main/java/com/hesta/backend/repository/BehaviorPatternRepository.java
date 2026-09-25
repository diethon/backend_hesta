package com.hesta.backend.repository;

import com.hesta.backend.entity.BehaviorPattern;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BehaviorPatternRepository extends JpaRepository<BehaviorPattern, UUID> {
}
