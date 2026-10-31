package com.hesta.backend.repository;

import com.hesta.backend.entity.FallIncident;
import com.hesta.backend.falldetection.enums.FallIncidentState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FallIncidentRepository extends JpaRepository<FallIncident, UUID> {
    List<FallIncident> findByHomeIdAndState(UUID homeId, FallIncidentState state);
    List<FallIncident> findByHomeIdOrderByDetectedAtDesc(UUID homeId);
}
