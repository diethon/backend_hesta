package com.hesta.backend.dto.response;

import lombok.*;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceAdminStatsResponse {

    private long totalCatalogs;
    private long totalDevices;
    private long unclaimedDevices;
    private long claimedDevices;
    private Map<String, Long> typeBreakdown;
}
