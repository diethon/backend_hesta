package com.hesta.backend.dto.response;

import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class DeviceResponse {
    private UUID id;
    private UUID homeId;
    private UUID roomId;
    private String roomName;
    private UUID nodeId;
    private String nodeName;
    private String name;
    private String deviceType;
    private String mqttTopic;
    private String status;
    private Map<String, Object> currentState;
    private List<String> capabilities;
    private String icon;
    private BigDecimal digitalTwinX;
    private BigDecimal digitalTwinY;
    private BigDecimal digitalTwinZ;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public static DeviceResponse fromEntity(Device device) {
        if (device == null) {
            return null;
        }

        Room room = device.getRoom();
        Home home = (room != null) ? room.getHome() : null;

        return DeviceResponse.builder()
                .id(device.getId())
                .roomId(device.getRoom() != null ? device.getRoom().getId() : null)
                .roomName(device.getRoom() != null ? device.getRoom().getName() : null)
                .homeId(home != null ? home.getId() : null)
                .roomId(room != null ? room.getId() : null)
                .roomName(room != null ? room.getName() : null)
                .nodeId(device.getNode() != null ? device.getNode().getId() : null)
                .nodeName(device.getNode() != null ? device.getNode().getNodeCode() : null)
                .name(device.getName())
                .deviceType(device.getDeviceType() != null ? device.getDeviceType().name() : null)
                .mqttTopic(device.getMqttTopic())
                .status(device.getStatus() != null ? device.getStatus().name() : null)

                .status(device.getStatus().name())
                .currentState(device.getCurrentState())
                .capabilities(device.getCapabilities())
                .icon(device.getIcon())
                .digitalTwinX(device.getDigitalTwinX())
                .digitalTwinY(device.getDigitalTwinY())
                .digitalTwinZ(device.getDigitalTwinZ())
                .createdAt(device.getCreatedAt())
                .updatedAt(device.getUpdatedAt())
                .build();
    }
}

