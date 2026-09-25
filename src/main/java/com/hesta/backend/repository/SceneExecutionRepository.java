package com.hesta.backend.repository;

import com.hesta.backend.entity.SceneExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SceneExecutionRepository extends JpaRepository<SceneExecution, UUID> {
    List<SceneExecution> findTop100BySceneIdOrderByStartedAtDesc(UUID sceneId);
}
