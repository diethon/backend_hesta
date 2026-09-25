package com.hesta.backend.entity;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;


@Entity
@Table(
        name = "sensor_readings",
        indexes = {
                @Index(
                        name = "idx_sensor_readings_device_time",
                        columnList = "device_id, recorded_at"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SensorReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Column(name = "metric_type", nullable = false, length = 50)
    private String metricType;

    @Column(name = "value", nullable = false, precision = 15, scale = 4)
    private BigDecimal value;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;
}