package com.hesta.backend.falldetection.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FallIncidentEventDTO {
    private String eventId;
    private String deviceId;
    private String homeId;
    private String zoneType; // CAMERA_VISION hoặc MMWAVE_RADAR
    private String eventType; // FALL_DETECTED
    private String incidentClassification; // ACCIDENTAL_FALL, etc.
    private Double confidence;
    private FallMetricsDTO metrics;
    private Map<String, Object> auxiliaryContext;
    private Long timestamp;
}
