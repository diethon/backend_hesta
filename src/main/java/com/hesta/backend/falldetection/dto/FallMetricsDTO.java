package com.hesta.backend.falldetection.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FallMetricsDTO {
    private Double normalizedVerticalVelocity;
    private Double peakImpactAccelG;
    private Double peakJerkGPerSec;
    private Double postureTransitionDurationSec;
    private Double bodyTiltAngleDegrees;
    private Double elevationZMetres;
    private String landingZone;
    private Double inactivitySeconds;
}
