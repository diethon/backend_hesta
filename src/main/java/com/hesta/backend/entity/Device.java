package com.hesta.backend.entity;

import com.hesta.backend.enums.DeviceStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "devices", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"node_id", "local_id"})
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
    @JoinColumn(name = "room_id")
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id")
    private EdgeNode node;

    @Column(name = "local_id", length = 50)
    private String localId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "device_type", nullable = false, length = 50)
    private String deviceType;

    @Column(name = "mqtt_topic", length = 255)
    private String mqttTopic;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeviceStatus status;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "current_state", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> currentState;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "capabilities", columnDefinition = "jsonb")
    @Builder.Default
    private java.util.Map<String, java.util.List<String>> capabilities = new java.util.HashMap<>();

    @Column(name = "icon", length = 50)
    private String icon;

    @Column(name = "digital_twin_x", precision = 10, scale = 3)
    private BigDecimal digitalTwinX;

    @Column(name = "digital_twin_y", precision = 10, scale = 3)
    private BigDecimal digitalTwinY;

    @Column(name = "digital_twin_z", precision = 10, scale = 3)
    private BigDecimal digitalTwinZ;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean isDeleted = false;

    public Home getHome() { return room != null ? room.getHome() : (node != null ? node.getHome() : null); }

    // DUMMY METHODS FOR COMPATIBILITY WITH TWIN HEALTH
    public OffsetDateTime getLastSeen() { return null; }

    public void setLastSeen(OffsetDateTime lastSeen) {}

    public boolean supportsAction(String action) {
        if (this.capabilities == null || this.capabilities.isEmpty()) return true;
        return this.capabilities.values().stream().flatMap(java.util.List::stream).anyMatch(act -> act.equalsIgnoreCase(action));
    }


}
