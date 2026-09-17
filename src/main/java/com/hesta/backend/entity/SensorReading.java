package com.hesta.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Mapping of the existing sensor_readings table; no Twin state is persisted. */
@Entity
@Table(name = "sensor_readings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SensorReading {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Column(name = "metric_type", nullable = false, length = 50)
    private String metricType;

    @Column(name = "value", nullable = false, precision = 10, scale = 3)
    private BigDecimal value;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;
}
