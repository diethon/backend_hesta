package com.hesta.backend.ota;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ota_campaign_nodes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ota_campaign_nodes_campaign_node", columnNames = {"campaign_id", "node_code"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtaCampaignNodeEntity {

    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "node_id")
    private UUID nodeId;

    @Column(name = "node_code", nullable = false, length = 100)
    private String nodeCode;

    @Column(name = "wave", nullable = false)
    private int wave;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "reported_version", length = 50)
    private String reportedVersion;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
