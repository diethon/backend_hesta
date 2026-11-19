package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCapabilityInfoResponse {
    private UUID id;
    private UUID homeId;
    private UUID roomId;
    private String roomName;
    private String name;
    private String deviceType;
    private String status;
    private String icon;
    private Map<String, List<String>> capabilities;
    private List<String> supportedActions;
    private List<String> supportedAttributes;
    private Map<String, Object> currentState;
    private boolean sensor;
    private boolean actionable;
}
