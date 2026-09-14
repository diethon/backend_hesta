package com.hesta.backend.entity;

import com.hesta.backend.enums.DeviceStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "devices", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"node_id", "gpio_pin"})
})
@org.hibernate.annotations.SQLRestriction("is_deleted = false")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_id", nullable = false)
    private Home home;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id")
    private EdgeNode node;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 50)
    private com.hesta.backend.enums.DeviceType deviceType;

    @Column(name = "gpio_pin")
    private Short gpioPin;

    @Column(name = "mqtt_topic", length = 255)
    private String mqttTopic;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeviceStatus status;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "current_state", nullable = false, columnDefinition = "jsonb")
    private java.util.Map<String, Object> currentState;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "capabilities", columnDefinition = "jsonb")
    @Builder.Default
    private java.util.List<String> capabilities = new java.util.ArrayList<>();

    @Column(name = "icon", length = 50)
    private String icon;

    @Column(name = "digital_twin_x", precision = 10, scale = 3)
    private BigDecimal digitalTwinX;

    @Column(name = "digital_twin_y", precision = 10, scale = 3)
    private BigDecimal digitalTwinY;

    @Column(name = "digital_twin_z", precision = 10, scale = 3)
    private BigDecimal digitalTwinZ;

    @Column(name = "last_seen")
    private OffsetDateTime lastSeen;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    
    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean isDeleted = false;
}
