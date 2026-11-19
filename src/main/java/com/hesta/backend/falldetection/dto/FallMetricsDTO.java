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

    @JsonAlias({"vNorm"})
    private Double normalizedVerticalVelocity;

    @JsonAlias({"accelG", "accel"})
    private Double peakImpactAccelG;

    @JsonAlias({"jerkGPerSec", "jerk"})
    private Double peakJerkGPerSec;

    @JsonAlias({"pttSec", "ptt"})
    private Double postureTransitionDurationSec;

    @JsonAlias({"spineAngle"})
    private Double bodyTiltAngleDegrees;

    @JsonAlias({"elevation_z", "elevationZ"})
    private Double elevationZMetres;

    @JsonAlias({"landing_zone", "landingZone", "zone"})
    private String landingZone;

    @JsonAlias({"inactivity_seconds", "inactivitySec"})
    private Double inactivitySeconds;

    // Direct accessors matching Python Edge AI & React Frontend keys
    private Double v_norm;
    private Double accel_g;
    private Double jerk_g_per_sec;
    private Double spine_angle;
    private Double ptt_sec;
    private Boolean has_freefall;
    private Boolean has_impact;
    private Double motion_energy;

    // Bidirectional fallback getters so both Backend (camelCase) and Frontend (snake_case) stay 100% synchronized
    public Double getNormalizedVerticalVelocity() {
        return normalizedVerticalVelocity != null ? normalizedVerticalVelocity : v_norm;
    }

    public Double getV_norm() {
        return v_norm != null ? v_norm : normalizedVerticalVelocity;
    }

    public Double getPeakImpactAccelG() {
        return peakImpactAccelG != null ? peakImpactAccelG : accel_g;
    }

    public Double getAccel_g() {
        return accel_g != null ? accel_g : peakImpactAccelG;
    }

    public Double getPeakJerkGPerSec() {
        return peakJerkGPerSec != null ? peakJerkGPerSec : jerk_g_per_sec;
    }

    public Double getJerk_g_per_sec() {
        return jerk_g_per_sec != null ? jerk_g_per_sec : peakJerkGPerSec;
    }

    public Double getPostureTransitionDurationSec() {
        return postureTransitionDurationSec != null ? postureTransitionDurationSec : ptt_sec;
    }

    public Double getPtt_sec() {
        return ptt_sec != null ? ptt_sec : postureTransitionDurationSec;
    }

    public Double getBodyTiltAngleDegrees() {
        return bodyTiltAngleDegrees != null ? bodyTiltAngleDegrees : spine_angle;
    }

    public Double getSpine_angle() {
        return spine_angle != null ? spine_angle : bodyTiltAngleDegrees;
    }
}
