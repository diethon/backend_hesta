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
public class DeviceEnergyBreakdown {
    private UUID deviceId;
    private String deviceName;
    private String deviceType;
    private String roomName;
    private double kwh;
    private double percentage;
    private long costVnd;
    private double totalOnHours;
    private int wattage;
}
