package com.hesta.backend.entity;

import com.hesta.backend.enums.MaintenanceActorRole;
import com.hesta.backend.enums.MaintenanceTicketStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "maintenance_ticket_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaintenanceTicketEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private MaintenanceTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 20)
    private MaintenanceActorRole actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private MaintenanceTicketStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private MaintenanceTicketStatus toStatus;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    // Set explicitly so several events added in one flush keep a stable order.
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
