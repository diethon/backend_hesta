package com.hesta.backend.entity;

import com.hesta.backend.enums.MaintenanceDeviceOutcome;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "maintenance_ticket_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaintenanceTicketDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private MaintenanceTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_anomaly_id")
    private DeviceAnomaly sourceAnomaly;

    @Column(name = "device_name", nullable = false, length = 150)
    private String deviceName;

    @Column(name = "device_type", length = 50)
    private String deviceType;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(name = "room_name", length = 100)
    private String roomName;

    @Column(name = "issue_note", columnDefinition = "TEXT")
    private String issueNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 30)
    private MaintenanceDeviceOutcome outcome;

    @Column(name = "outcome_note", columnDefinition = "TEXT")
    private String outcomeNote;
}
