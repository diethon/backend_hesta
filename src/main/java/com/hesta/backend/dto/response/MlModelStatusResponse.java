package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MlModelStatusResponse {
    private String status;
    private String version;
    private String modelType;
    private String trainedAt;
    private Integer trainSamples;
    private Integer testSamples;
    private Double accuracy;
    private Double precision;
    private Double recall;
    private Double f1Score;
    private Double baselineAccuracy;
    private Double baselineF1;
    private Double confidenceThreshold;
    private Boolean calibrated;
    private Map<String, Object> labelDistribution;
    private Map<String, Object> metrics;
}
