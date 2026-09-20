package com.hesta.backend.repository;

import com.hesta.backend.entity.TwinNodeLayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TwinNodeLayoutRepository extends JpaRepository<TwinNodeLayout, UUID> {
    List<TwinNodeLayout> findByLayoutIdOrderByNodeTypeAscNodeIdAsc(UUID layoutId);

    void deleteByLayoutId(UUID layoutId);
}
