package com.hesta.backend.repository;

import com.hesta.backend.entity.MaintenanceAssistantSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceAssistantSettingRepository extends JpaRepository<MaintenanceAssistantSetting, String> {
}
