package com.hesta.backend.falldetection.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FallMetricsDTO {

    @JsonAlias({"v_norm", "vNorm"})
    private Double normalizedVerticalVelocity;

    @JsonAlias({"accel_g", "accelG", "accel"})
    private Double peakImpactAccelG;

    @JsonAlias({"jerk_g_per_sec", "jerkGPerSec", "jerk"})
    private Double peakJerkGPerSec;

    @JsonAlias({"ptt_sec", "pttSec", "ptt"})
    private Double postureTransitionDurationSec;

    @JsonAlias({"spine_angle", "spineAngle"})
    private Double bodyTiltAngleDegrees;

    @JsonAlias({"elevation_z", "elevationZ"})
    private Double elevationZMetres;

    @JsonAlias({"landing_zone", "landingZone", "zone"})
    private String landingZone;

    @JsonAlias({"inactivity_seconds", "inactivitySec", "motion_energy"})
    private Double inactivitySeconds;

    // Direct accessors matching Python dict keys
    @JsonAlias("v_norm")
    private Double v_norm;

    @JsonAlias("accel_g")
    private Double accel_g;

    @JsonAlias("jerk_g_per_sec")
    private Double jerk_g_per_sec;

    @JsonAlias("spine_angle")
    private Double spine_angle;

    @JsonAlias("ptt_sec")
    private Double ptt_sec;

    @JsonAlias("has_freefall")
    private Boolean has_freefall;

    @JsonAlias("has_impact")
    private Boolean has_impact;

    @JsonAlias("motion_energy")
    private Double motion_energy;
}
