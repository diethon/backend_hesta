package com.hesta.backend.repository;

import com.hesta.backend.entity.BehaviorEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface BehaviorEventRepository extends JpaRepository<BehaviorEvent, UUID> {
    List<BehaviorEvent> findAllByHomeIdAndOccurredAtBetweenOrderByOccurredAtAsc(
            UUID homeId, OffsetDateTime from, OffsetDateTime to);

    @Modifying
    @Query("delete from BehaviorEvent event where event.home.id = :homeId and event.datasetKey = :datasetKey")
    void deleteGeneratedDataset(UUID homeId, String datasetKey);
}
