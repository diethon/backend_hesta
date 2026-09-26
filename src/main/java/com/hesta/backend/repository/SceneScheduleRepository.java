package com.hesta.backend.repository;

import com.hesta.backend.entity.SceneSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.time.LocalTime;

import java.util.List;
import java.util.UUID;

public interface SceneScheduleRepository extends JpaRepository<SceneSchedule, UUID> {
    List<SceneSchedule> findAllBySceneIdOrderByScheduledTimeAsc(UUID sceneId);

    @EntityGraph(attributePaths = {"scene", "scene.home"})
    List<SceneSchedule> findAllByActiveTrueAndScheduledTime(LocalTime scheduledTime);
}
