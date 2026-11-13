package com.hesta.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GateCommandRequest {

    @NotBlank(message = "INVALID_DEVICE_ACTION")
    private String action;

    private String nodeId;

    /**
     * Converts properties into a parameter map compatible with DeviceCommandService.
     */
    public Map<String, Object> toParameters() {
        Map<String, Object> params = new HashMap<>();
        if (nodeId != null && !nodeId.isBlank()) {
            params.put("nodeId", nodeId.trim());
        }
        return params;
    }
}
