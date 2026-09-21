package com.hesta.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAutomationRuleRequest {
    @NotBlank(message = "AUTOMATION_NAME_INVALID")
    @Size(max = 150, message = "AUTOMATION_NAME_INVALID")
    String name;

    @Size(max = 2000, message = "AUTOMATION_NAME_INVALID")
    String description;

    @NotBlank(message = "AUTOMATION_TRIGGER_INVALID")
    String triggerType;

    @NotNull(message = "AUTOMATION_ENABLED_REQUIRED")
    Boolean enabled;

    @Valid
    @NotEmpty(message = "AUTOMATION_CONDITION_INVALID")
    List<RuleConditionRequest> conditions;

    @Valid
    @NotEmpty(message = "AUTOMATION_ACTION_INVALID")
    List<RuleActionRequest> actions;
}
