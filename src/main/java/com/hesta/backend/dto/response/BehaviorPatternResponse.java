package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;
import java.util.UUID;

@Data
@Builder
public class BehaviorPatternResponse {
    String patternType;
    UUID deviceId;
    String deviceName;
    UUID roomId;
    String roomName;
    String action;
    LocalTime averageTime;
    long occurrences;
    double confidence;
}
