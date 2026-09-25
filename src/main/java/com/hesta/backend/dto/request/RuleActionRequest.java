package com.hesta.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleActionRequest {
    UUID deviceId;

    UUID sceneId;

    @NotBlank(message = "AUTOMATION_ACTION_INVALID")
    String action;

    @Builder.Default
    Map<String, Object> parameters = new HashMap<>();

    @NotNull(message = "AUTOMATION_ACTION_INVALID")
    @Min(value = 0, message = "AUTOMATION_ACTION_INVALID")
    @Max(value = 32767, message = "AUTOMATION_ACTION_INVALID")
    Integer order;
}
