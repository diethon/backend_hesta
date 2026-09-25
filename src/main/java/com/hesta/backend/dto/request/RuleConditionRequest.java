package com.hesta.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleConditionRequest {
    UUID deviceId;

    @NotBlank(message = "AUTOMATION_CONDITION_INVALID")
    @Size(max = 100, message = "AUTOMATION_CONDITION_INVALID")
    String attribute;

    @NotBlank(message = "AUTOMATION_CONDITION_INVALID")
    String operator;

    @NotNull(message = "AUTOMATION_CONDITION_INVALID")
    Object expectedValue;

    @Builder.Default
    String logicalOperator = "AND";

    @NotNull(message = "AUTOMATION_CONDITION_INVALID")
    @Min(value = 0, message = "AUTOMATION_CONDITION_INVALID")
    @Max(value = 32767, message = "AUTOMATION_CONDITION_INVALID")
    Integer order;
}
