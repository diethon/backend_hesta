package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
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
    private Map<String, Object> parameters;
    private String triggerType;
    private String time;
    /** ISO days 1=Mon..7=Sun; empty = every day. */
    private java.util.List<Integer> repeatDays;
    private Double confidence;
    private Boolean eligible;
    private String reason;
    private String engineType;
    private String modelVersion;
}
