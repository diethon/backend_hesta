package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MlRecommendationProposal {
    private UUID deviceId;
    private String deviceName;
    private UUID roomId;
    private String roomName;
    private String action;
    private String triggerType;
    private String time;
    private Double confidence;
    private Boolean eligible;
    private String reason;
    private String engineType;
    private String modelVersion;
}
