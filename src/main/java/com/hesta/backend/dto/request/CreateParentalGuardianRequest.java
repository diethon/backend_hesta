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
public class CreateParentalGuardianRequest {

    @NotBlank(message = "Tên quy tắc không được để trống")
    private String name;

    @NotNull(message = "Thiết bị giám sát (TV/máy tính/ổ cắm) không được để trống")
    private UUID targetDeviceId;

    private UUID cameraDeviceId;

    @Min(value = 1, message = "Thời gian cho phép tối thiểu là 1 phút")
    @Max(value = 480, message = "Thời gian cho phép tối đa là 480 phút")
    @Builder.Default
    private int allowedDurationMinutes = 45;

    @Min(value = 1, message = "Thời gian cảnh báo trước tối thiểu là 1 phút")
    @Max(value = 30, message = "Thời gian cảnh báo trước tối đa là 30 phút")
    @Builder.Default
    private int warningMinutesBefore = 5;

    @Builder.Default
    private boolean autoLockout = true;
}
