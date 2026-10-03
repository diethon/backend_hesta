package com.hesta.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "twin_room_layouts", uniqueConstraints = @UniqueConstraint(columnNames = {"layout_id", "room_id"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TwinRoomLayout {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "layout_id", nullable = false)
    private UUID layoutId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "floor_number", nullable = false)
    private short floor;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal x;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal y;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal width;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal height;
}
