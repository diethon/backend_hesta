package com.hesta.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceBatchGenerateQrRequest {

    @NotNull(message = "Mã danh mục thiết bị (catalogId) là bắt buộc")
    private UUID catalogId;

    @Min(value = 1, message = "Số lượng sinh tối thiểu là 1")
    @Max(value = 50, message = "Số lượng sinh tối đa mỗi lần là 50")
    @Builder.Default
    private int quantity = 1;

    private String customName;
}
