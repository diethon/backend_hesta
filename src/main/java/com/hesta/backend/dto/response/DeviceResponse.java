package com.hesta.backend.dto.response;

import com.hesta.backend.entity.Device;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class DeviceResponse {
    private UUID id;
    private UUID homeId;
    private UUID roomId;
    private UUID nodeId;
    private String name;
    private String deviceType;
    private Short gpioPin;
    private String status;
    private Map<String, Object> currentState;
    private String icon;
    private BigDecimal digitalTwinX;
    private BigDecimal digitalTwinY;
    private BigDecimal digitalTwinZ;
    private OffsetDateTime lastSeen;

    public static DeviceResponse fromEntity(Device device) {
        return DeviceResponse.builder()
                .id(device.getId())
                .homeId(device.getHome().getId())
                .roomId(device.getRoom() != null ? device.getRoom().getId() : null)
                .nodeId(device.getNode() != null ? device.getNode().getId() : null)
                .name(device.getName())
                .deviceType(device.getDeviceType())
                .gpioPin(device.getGpioPin())
                .status(device.getStatus().name())
                .currentState(device.getCurrentState())
                .icon(device.getIcon())
                .digitalTwinX(device.getDigitalTwinX())
                .digitalTwinY(device.getDigitalTwinY())
                .digitalTwinZ(device.getDigitalTwinZ())
                .lastSeen(device.getLastSeen())
                .build();
    }
}
