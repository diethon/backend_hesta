package com.hesta.backend.ota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtaCampaignNodeDto {
    private UUID id;
    private UUID nodeId;
    private String nodeCode;
    private int wave;
    private String status;
    private String reportedVersion;
    private String errorMessage;
    private OffsetDateTime dispatchedAt;
    private OffsetDateTime completedAt;
}
