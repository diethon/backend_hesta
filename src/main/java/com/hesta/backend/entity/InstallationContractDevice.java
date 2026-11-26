package com.hesta.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "installation_contract_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstallationContractDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id", nullable = false)
    private InstallationContract contract;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_item_id")
    private InstallationContractItem contractItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @Column(name = "device_name", nullable = false, length = 150)
    private String deviceName;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(name = "room_name", length = 100)
    private String roomName;

    @Column(name = "warranty_months", nullable = false)
    @Builder.Default
    private Integer warrantyMonths = 12;

    @Column(name = "warranty_expires_at")
    private OffsetDateTime warrantyExpiresAt;

    @CreationTimestamp
    @Column(name = "provisioned_at", nullable = false, updatable = false)
    private OffsetDateTime provisionedAt;
}
