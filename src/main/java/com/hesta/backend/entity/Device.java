package com.hesta.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.hesta.backend.enums.DeviceStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_state", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> currentState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capabilities", columnDefinition = "jsonb")
    @Builder.Default
    private DeviceCapabilities capabilities = new DeviceCapabilities();

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

    @JsonProperty("capabilities")
    @JsonDeserialize(using = DeviceCapabilitiesDeserializer.class)
    public void setCapabilities(DeviceCapabilities capabilities) {
        this.capabilities = capabilities != null ? capabilities : new DeviceCapabilities();
    }

    @JsonIgnore
    public void setCapabilities(Map<String, List<String>> capabilities) {
        if (capabilities instanceof DeviceCapabilities dc) {
            this.capabilities = dc;
        } else if (capabilities != null) {
            this.capabilities = new DeviceCapabilities(capabilities);
        } else {
            this.capabilities = new DeviceCapabilities();
        }
    }

    public static class DeviceBuilder {
        public DeviceBuilder capabilities(Map<String, List<String>> capabilities) {
            if (capabilities instanceof DeviceCapabilities dc) {
                this.capabilities$value = dc;
            } else if (capabilities != null) {
                this.capabilities$value = new DeviceCapabilities(capabilities);
            } else {
                this.capabilities$value = new DeviceCapabilities();
            }
            this.capabilities$set = true;
            return this;
        }
    }
}
