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
public class MlPredictionResponse {
    private UUID deviceId;
    private String deviceName;
    private String action;
    private Double confidence;
    private Boolean confidenceCalibrated;
    private Boolean eligible;
    private Double threshold;
    private String reason;
    private String modelVersion;
    private String status;
    private String predictedTime;
}
