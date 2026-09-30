package com.hesta.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hesta.backend.enums.ExecutionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "scene_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SceneExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scene_id", nullable = false)
    private Scene scene;

    @Column(name = "trigger_source", nullable = false, length = 20)
    private String triggerSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExecutionStatus status;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at", nullable = false)
    private OffsetDateTime completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_detail", columnDefinition = "jsonb", nullable = false)
    private JsonNode resultDetail;
}
