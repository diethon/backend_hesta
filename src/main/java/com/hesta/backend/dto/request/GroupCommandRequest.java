package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupCommandRequest {

    @NotBlank(message = "Lệnh điều khiển không được để trống")
    private String action;

    @Builder.Default
    private Map<String, Object> parameters = Map.of();
}
