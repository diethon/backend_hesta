package com.hesta.backend.entity;

import com.hesta.backend.enums.MaintenancePriority;
import com.hesta.backend.enums.MaintenanceQuoteDecision;
import com.hesta.backend.enums.MaintenanceTicketStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "maintenance_tickets")
@Getter
@Setter
@NoArgsConstructor
public class MaintenanceTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_id", nullable = false)
    private Home home;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private MaintenancePriority priority = MaintenancePriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MaintenanceTicketStatus status = MaintenanceTicketStatus.SUBMITTED;

    @Column(name = "scheduled_start")
    private OffsetDateTime scheduledStart;

    @Column(name = "scheduled_end")
    private OffsetDateTime scheduledEnd;

    @Column(name = "schedule_confirmed", nullable = false)
    private boolean scheduleConfirmed = false;

    @Column(name = "admin_note", columnDefinition = "TEXT")
    private String adminNote;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Column(name = "findings", columnDefinition = "TEXT")
    private String findings;

    @Column(name = "warranty_months")
    private Integer warrantyMonths;

    @Column(name = "quote_total", precision = 14, scale = 2)
    private BigDecimal quoteTotal;

    @Column(name = "quote_submitted_at")
    private OffsetDateTime quoteSubmittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "quote_decision", length = 20)
    private MaintenanceQuoteDecision quoteDecision;

    @Column(name = "quote_decided_at")
    private OffsetDateTime quoteDecidedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "rating")
    private Short rating;

    @Column(name = "feedback", columnDefinition = "TEXT")
    private String feedback;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("deviceName ASC")
    private List<MaintenanceTicketDevice> devices = new ArrayList<>();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startAt ASC")
    private List<MaintenanceAvailabilitySlot> slots = new ArrayList<>();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<MaintenanceQuoteItem> quoteItems = new ArrayList<>();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<MaintenanceTicketEvent> events = new ArrayList<>();

    public void addDevice(MaintenanceTicketDevice device) {
        device.setTicket(this);
        devices.add(device);
    }

    public void replaceSlots(List<MaintenanceAvailabilitySlot> newSlots) {
        slots.clear();
        newSlots.forEach(slot -> {
            slot.setTicket(this);
            slots.add(slot);
        });
    }

    public void replaceQuoteItems(List<MaintenanceQuoteItem> items) {
        quoteItems.clear();
        items.forEach(item -> {
            item.setTicket(this);
            quoteItems.add(item);
        });
    }

    public void addEvent(MaintenanceTicketEvent event) {
        event.setTicket(this);
        events.add(event);
    }
}
