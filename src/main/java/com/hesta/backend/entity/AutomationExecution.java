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
@Table(name = "automation_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutomationExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private AutomationRule rule;

    @Column(name = "trigger_source", length = 50)
    private String triggerSource;

    @Column(name = "matched_at")
    private OffsetDateTime matchedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExecutionStatus status;

    @Column(name = "is_test", nullable = false)
    private boolean test;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_detail", columnDefinition = "jsonb")
    private JsonNode resultDetail;
}
