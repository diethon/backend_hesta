package com.hesta.backend.repository;

import com.hesta.backend.entity.MaintenanceChatEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MaintenanceChatEventRepository extends JpaRepository<MaintenanceChatEvent, UUID> {
}
