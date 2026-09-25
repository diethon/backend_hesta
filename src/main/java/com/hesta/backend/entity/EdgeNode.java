package com.hesta.backend.entity;


import com.hesta.backend.enums.EdgeNodeStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "edge_nodes", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"home_id", "node_code"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EdgeNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_id", nullable = false)
    private Home home;


    @Column(name = "node_code", nullable = false, length = 100)
    private String nodeCode;


    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EdgeNodeStatus status;

    @CreationTimestamp
    @Column(name = "paired_at", nullable = false, updatable = false)
    private OffsetDateTime pairedAt;

}
