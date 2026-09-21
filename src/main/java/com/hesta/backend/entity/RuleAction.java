package com.hesta.backend.entity;

import com.hesta.backend.enums.DeviceAction;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "rule_actions", uniqueConstraints =
        @UniqueConstraint(name = "uq_rule_actions_rule_order", columnNames = {"rule_id", "order_index"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleAction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private AutomationRule rule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_command", nullable = false, length = 100)
    private DeviceAction action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "order_index", nullable = false, columnDefinition = "smallint")
    private int order;
}
