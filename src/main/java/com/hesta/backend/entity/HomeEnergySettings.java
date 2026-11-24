package com.hesta.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "home_energy_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HomeEnergySettings {

    @Id
    @Column(name = "home_id", nullable = false)
    private UUID homeId;

    @Column(name = "dr_enabled", nullable = false)
    private boolean drEnabled;

    @Column(name = "peak_threshold_watts", nullable = false, precision = 10, scale = 2)
    private BigDecimal peakThresholdWatts;

    @Column(name = "dr_mode", nullable = false, length = 30)
    private String drMode;

    @Column(name = "billing_start_day", nullable = false)
    private int billingStartDay;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
