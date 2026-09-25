package com.hesta.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "behavior_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BehaviorEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_id", nullable = false)
    private Home home;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "action", length = 100)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_state", columnDefinition = "jsonb")
    private Map<String, Object> previousState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_state", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> currentState = new HashMap<>();

    @Column(name = "event_source", length = 50)
    private String eventSource;

    @Column(name = "dataset_key", length = 100)
    private String datasetKey;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;
}
