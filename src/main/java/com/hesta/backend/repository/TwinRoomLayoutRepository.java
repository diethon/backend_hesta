package com.hesta.backend.repository;

import com.hesta.backend.entity.TwinRoomLayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TwinRoomLayoutRepository extends JpaRepository<TwinRoomLayout, UUID> {
    List<TwinRoomLayout> findByLayoutIdOrderByRoomId(UUID layoutId);

    void deleteByLayoutId(UUID layoutId);
}
