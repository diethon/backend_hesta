package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
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
public class DeviceGroupRequest {

    @NotBlank(message = "Tên nhóm thiết bị không được để trống")
    private String name;

    private String icon;

    private List<UUID> deviceIds;
}
