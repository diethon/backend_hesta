package com.hesta.backend.repository;

import com.hesta.backend.entity.EdgeNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EdgeNodeRepository extends JpaRepository<EdgeNode, UUID> {
    @Query("SELECT n FROM EdgeNode n WHERE n.home.id = :homeId AND n.nodeCode = :nodeCode")
    Optional<EdgeNode> findByHomeIdAndNodeCode(@Param("homeId") UUID homeId, @Param("nodeCode") String nodeCode);

    Optional<EdgeNode> findByNodeCode(String nodeCode);
    java.util.List<EdgeNode> findByHome_Id(UUID homeId);

    @Query("SELECT n FROM EdgeNode n LEFT JOIN FETCH n.home")
    java.util.List<EdgeNode> findAllWithHome();

    @Query("SELECT n FROM EdgeNode n LEFT JOIN FETCH n.home WHERE n.id = :id")
    Optional<EdgeNode> findByIdWithHome(@Param("id") UUID id);
}
