package com.hesta.backend.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class DeviceUpdateRequest {
    
    @Size(max = 150, message = "Tên thiết bị không được vượt quá 150 ký tự")
    private String name;
    
    private UUID roomId;
    
    @Size(max = 50, message = "Icon không hợp lệ")
    private String icon;
    
    private BigDecimal digitalTwinX;
    private BigDecimal digitalTwinY;
    private BigDecimal digitalTwinZ;
}
