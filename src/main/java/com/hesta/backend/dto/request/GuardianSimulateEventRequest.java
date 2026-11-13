package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardianSimulateEventRequest {

    /**
     * Supported simulation events:
     * - PARENT_DEPARTURE: Giả lập camera phát hiện phụ huynh ra khỏi nhà (kích hoạt chế độ giám hộ).
     * - PARENT_ARRIVAL: Giả lập phụ huynh về nhà (tắt chế độ giám hộ).
     * - DEVICE_POWER_ON: Giả lập con bật thiết bị (TV / PC).
     * - DEVICE_POWER_OFF: Giả lập thiết bị tắt.
     * - FAST_FORWARD_EXPIRE: Giả lập hết giờ sử dụng ngay lập tức (để demo thuyết trình).
     * - TAMPER_POWER_ON: Giả lập con cố tình bấm bật lại khi đang bị khóa (kiểm tra chống lách luật).
     */
    @NotBlank(message = "Loại sự kiện giả lập không được để trống")
    private String eventType;
}
