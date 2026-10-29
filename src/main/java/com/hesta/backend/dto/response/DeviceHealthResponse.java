package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceHealthResponse {
    private Integer switchCycles;
    private Integer maxRatedCycles;
    private Double healthPercent;
    private Boolean chatteringWarning;
    private Boolean safetyTripped;
    private OffsetDateTime lastCycleAt;
}
