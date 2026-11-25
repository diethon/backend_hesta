package com.hesta.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "device_energy_rollups",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_device_energy_rollup_bucket", columnNames = {"device_id", "bucket_start"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceEnergyRollup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "home_id", nullable = false)
    private UUID homeId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "bucket_start", nullable = false)
    private OffsetDateTime bucketStart;

    @Column(name = "kwh", nullable = false, precision = 12, scale = 4)
    private BigDecimal kwh;

    @Column(name = "on_seconds", nullable = false)
    private Integer onSeconds;

    @Column(name = "effective_watts", nullable = false, precision = 10, scale = 2)
    private BigDecimal effectiveWatts;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
