package com.hesta.backend.repository;

import com.hesta.backend.entity.Scene;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SceneRepository extends JpaRepository<Scene, UUID> {
    @EntityGraph(attributePaths = {"home", "actions", "actions.targetDevice"})
    List<Scene> findAllByHomeIdOrderByNameAsc(UUID homeId);

    @EntityGraph(attributePaths = {"home", "actions", "actions.targetDevice"})
    java.util.Optional<Scene> findByIdAndHomeId(UUID id, UUID homeId);

    boolean existsByHomeIdAndName(UUID homeId, String name);
    boolean existsByHomeIdAndNameAndIdNot(UUID homeId, String name, UUID id);
}
