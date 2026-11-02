package com.hesta.backend.repository;

import com.hesta.backend.entity.BehaviorEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface BehaviorEventRepository extends JpaRepository<BehaviorEvent, UUID> {
    java.util.Optional<BehaviorEvent> findTopByDeviceIdAndEventTypeOrderByOccurredAtDesc(UUID deviceId, String eventType);

    @Query("""
            SELECT be FROM BehaviorEvent be
            WHERE be.device.id IN :deviceIds
              AND be.eventType = :eventType
              AND be.occurredAt = (
                  SELECT MAX(be2.occurredAt) FROM BehaviorEvent be2
                  WHERE be2.device.id = be.device.id AND be2.eventType = :eventType
              )
            """)
    List<BehaviorEvent> findLatestEventsByDeviceIdsAndEventType(
            @org.springframework.data.repository.query.Param("deviceIds") java.util.Collection<UUID> deviceIds,
            @org.springframework.data.repository.query.Param("eventType") String eventType);

    List<BehaviorEvent> findTop100ByDeviceIdAndEventTypeOrderByOccurredAtDesc(UUID deviceId, String eventType);
    List<BehaviorEvent> findAllByHomeIdAndOccurredAtBetweenOrderByOccurredAtAsc(
            UUID homeId, OffsetDateTime from, OffsetDateTime to);

    @Modifying
    @Query("delete from BehaviorEvent event where event.home.id = :homeId and event.datasetKey = :datasetKey")
    void deleteGeneratedDataset(UUID homeId, String datasetKey);
}
