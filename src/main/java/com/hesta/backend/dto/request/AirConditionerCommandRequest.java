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
public class AirConditionerCommandRequest {

    @NotBlank(message = "INVALID_DEVICE_ACTION")
    private String action;

    private Boolean power;

    private Integer temperature;

    private String fan;

    private String mode;

    private Boolean swing;

    private Integer hour;

    private Boolean halfHour;

    /**
     * Converts properties into a parameter map compatible with DeviceCommandService.
     */
    public Map<String, Object> toParameters() {
        Map<String, Object> params = new HashMap<>();
        if (power != null) {
            params.put("power", power);
        }
        if (temperature != null) {
            params.put("temperature", temperature);
        }
        if (fan != null && !fan.isBlank()) {
            params.put("fan", fan.trim().toUpperCase());
        }
        if (mode != null && !mode.isBlank()) {
            params.put("mode", mode.trim().toUpperCase());
        }
        if (swing != null) {
            params.put("swing", swing);
        }
        if (hour != null) {
            params.put("hour", hour);
        }
        if (halfHour != null) {
            params.put("halfHour", halfHour);
        }
        return params;
    }
}
