package com.hesta.backend.repository;

import com.hesta.backend.entity.EdgeNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EdgeNodeRepository extends JpaRepository<EdgeNode, UUID> {
    Optional<EdgeNode> findByHomeIdAndNodeCode(UUID homeId, String nodeCode);
    Optional<EdgeNode> findByMacAddress(String macAddress);
}
