package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCreateDeviceRequest {

    @NotBlank(message = "Tên thiết bị không được để trống")
    @Size(max = 150, message = "Tên thiết bị tối đa 150 ký tự")
    private String name;

    @NotBlank(message = "Loại thiết bị không được để trống")
    @Size(max = 50, message = "Loại thiết bị tối đa 50 ký tự")
    private String deviceType;

    @Size(max = 100, message = "Model tối đa 100 ký tự")
    private String model;

    @NotBlank(message = "Số serial không được để trống")
    @Size(max = 100, message = "Số serial tối đa 100 ký tự")
    private String serialNumber;

    @Size(max = 50, message = "localId tối đa 50 ký tự")
    private String localId;

    @Size(max = 50, message = "Icon tối đa 50 ký tự")
    private String icon;

    private Map<String, List<String>> capabilities;
}
