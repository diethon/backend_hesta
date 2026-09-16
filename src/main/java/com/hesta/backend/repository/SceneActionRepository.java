package com.hesta.backend.repository;

import com.hesta.backend.entity.SceneAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SceneActionRepository extends JpaRepository<SceneAction, UUID> {
    List<SceneAction> findAllBySceneIdOrderByOrderAsc(UUID sceneId);
    boolean existsBySceneIdAndOrder(UUID sceneId, int order);
    boolean existsBySceneIdAndOrderAndIdNot(UUID sceneId, int order, UUID id);
    long countBySceneId(UUID sceneId);

    @Modifying
    @Query(value = "SET CONSTRAINTS uq_scene_actions_scene_order DEFERRED", nativeQuery = true)
    void deferOrderConstraint();
}
