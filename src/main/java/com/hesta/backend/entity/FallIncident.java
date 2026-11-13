package com.hesta.backend.entity;

import com.hesta.backend.falldetection.enums.FallIncidentState;
import com.hesta.backend.falldetection.enums.FallIncidentType;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "fall_incidents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FallIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "home_id", nullable = false)
    private UUID homeId;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "room_name", length = 100)
    private String roomName;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_type", nullable = false, length = 50)
    private FallIncidentType incidentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 50)
    private FallIncidentState state;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "snapshot_url", length = 500)
    private String snapshotUrl;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (detectedAt == null) {
            detectedAt = OffsetDateTime.now();
        }
    }
}
