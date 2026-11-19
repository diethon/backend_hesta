package com.hesta.backend.dto.response;

import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceGroup;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceGroupResponse {

    private UUID id;
    private UUID homeId;
    private String name;
    private String icon;
    private int deviceCount;
    private List<UUID> deviceIds;
    private List<DeviceResponse> devices;
    private OffsetDateTime createdAt;

    public static DeviceGroupResponse fromEntity(DeviceGroup group) {
        if (group == null) return null;
        return fromEntity(group, group.getDevices());
    }

    public static DeviceGroupResponse fromEntity(DeviceGroup group, List<Device> visibleDevices) {
        if (group == null) return null;
        List<DeviceResponse> deviceResponses = visibleDevices == null ? List.of()
                : visibleDevices.stream().map(DeviceResponse::fromEntity).collect(Collectors.toList());
        List<UUID> ids = visibleDevices == null ? List.of()
                : visibleDevices.stream().map(Device::getId).collect(Collectors.toList());

        return DeviceGroupResponse.builder()
                .id(group.getId())
                .homeId(group.getHome() != null ? group.getHome().getId() : null)
                .name(group.getName())
                .icon(group.getIcon() != null ? group.getIcon() : "layers")
                .deviceCount(ids.size())
                .deviceIds(ids)
                .devices(deviceResponses)
                .createdAt(group.getCreatedAt())
                .build();
    }
}
