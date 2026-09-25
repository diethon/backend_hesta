package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;
import java.util.UUID;

@Data
@Builder
public class BehaviorPredictionResponse {
    UUID deviceId;
    String deviceName;
    String action;
    LocalTime predictedTime;
    double confidence;
    String reason;
}
