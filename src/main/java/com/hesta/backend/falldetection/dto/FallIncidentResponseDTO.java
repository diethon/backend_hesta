package com.hesta.backend.falldetection.dto;

import com.hesta.backend.falldetection.enums.FallIncidentState;
import com.hesta.backend.falldetection.enums.FallIncidentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FallIncidentResponseDTO {
    private UUID id;
    private UUID homeId;
    private String deviceId;
    private String roomName;
    private FallIncidentType incidentType;
    private FallIncidentState state;
    private Double confidenceScore;
    private Integer countdownRemainingSec;
    private FallMetricsDTO metrics;
    private Instant detectedAt;
    private Instant resolvedAt;
    private String resolutionNote;
}
