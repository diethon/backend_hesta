package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ManualDeviceCommandRequest {
    @NotBlank(message = "AUTOMATION_ACTION_INVALID")
    private String action;
}
