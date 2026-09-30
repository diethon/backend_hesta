package com.hesta.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hesta.backend.enums.ConditionOperator;
import com.hesta.backend.enums.LogicalOperator;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "rule_conditions", uniqueConstraints =
        @UniqueConstraint(name = "uq_rule_conditions_rule_order", columnNames = {"rule_id", "order_index"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleCondition {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private AutomationRule rule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    @Column(name = "attribute", nullable = false, length = 100)
    private String attribute;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false, length = 10)
    private ConditionOperator operator;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_value", nullable = false, columnDefinition = "jsonb")
    private JsonNode expectedValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "logical_group", nullable = false, length = 10)
    @Builder.Default
    private LogicalOperator logicalOperator = LogicalOperator.AND;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "order_index", nullable = false, columnDefinition = "smallint")
    private int order;
}
