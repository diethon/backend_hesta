package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceUptimeResponse {
    private Double uptimePercent;
    private Double totalHours;
    private Double onHours;
    private Double offHours;
    private List<TimelinePoint> timelinePoints;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelinePoint {
        private String timestamp;
        private String state;
        private Long durationSeconds;
    }
}
