package com.hesta.backend.repository;

import com.hesta.backend.entity.HomeEnergySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface HomeEnergySettingsRepository extends JpaRepository<HomeEnergySettings, UUID> {
}
