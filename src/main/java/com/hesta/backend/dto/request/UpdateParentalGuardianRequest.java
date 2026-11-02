package com.hesta.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateParentalGuardianRequest {

    @NotBlank(message = "Tên quy tắc không được để trống")
    private String name;

    @NotNull(message = "Thiết bị giám sát không được để trống")
    private UUID targetDeviceId;

    private UUID cameraDeviceId;

    @Min(value = 1, message = "Thời gian cho phép tối thiểu là 1 phút")
    @Max(value = 480, message = "Thời gian cho phép tối đa là 480 phút")
    private int allowedDurationMinutes;

    @Min(value = 1, message = "Thời gian cảnh báo trước tối thiểu là 1 phút")
    @Max(value = 30, message = "Thời gian cảnh báo trước tối đa là 30 phút")
    private int warningMinutesBefore;

    private boolean autoLockout;

    private boolean enabled;
}
