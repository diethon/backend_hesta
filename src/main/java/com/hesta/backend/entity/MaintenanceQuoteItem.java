package com.hesta.backend.entity;

import com.hesta.backend.enums.MaintenanceItemType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "maintenance_quote_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaintenanceQuoteItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private MaintenanceTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_device_id")
    private MaintenanceTicketDevice ticketDevice;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    private MaintenanceItemType itemType;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
