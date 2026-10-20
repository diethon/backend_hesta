package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeCapabilitiesResponse {
    private UUID homeId;
    private List<DeviceCapabilityInfoResponse> devices;
    private List<CapabilityGroupResponse> capabilities;
    private List<String> actionTypes;
    private List<String> sensorAttributes;
}
