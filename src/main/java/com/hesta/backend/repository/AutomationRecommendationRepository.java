package com.hesta.backend.repository;

import com.hesta.backend.entity.AutomationRecommendation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AutomationRecommendationRepository extends JpaRepository<AutomationRecommendation, UUID> {
    @EntityGraph(attributePaths = {"device"})
    List<AutomationRecommendation> findAllByHomeIdOrderByCreatedAtDesc(UUID homeId);

    @EntityGraph(attributePaths = {"device"})
    Optional<AutomationRecommendation> findByIdAndHomeId(UUID id, UUID homeId);

    boolean existsByHomeIdAndDeviceIdAndStatus(UUID homeId, UUID deviceId, String status);
}
