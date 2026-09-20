package com.hesta.backend.entity;

import com.hesta.backend.enums.TwinNodeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "twin_node_layouts", uniqueConstraints = @UniqueConstraint(columnNames = {"layout_id", "node_type", "node_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TwinNodeLayout {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "layout_id", nullable = false)
    private UUID layoutId;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 20)
    private TwinNodeType nodeType;

    @Column(name = "node_id", nullable = false, length = 255)
    private String nodeId;

    @Column(name = "room_id")
    private UUID roomId;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal x;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal y;
}
