package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AutomationRuleResponse {
    UUID id;
    UUID homeId;
    String name;
    String description;
    String triggerType;
    boolean enabled;
    int holdSeconds;
    List<RuleConditionResponse> conditions;
    List<RuleActionResponse> actions;
    /** Times of a SCHEDULE rule, so lists can say "18:00 hằng ngày" instead of "at the set time". */
    List<ScheduleResponse> schedules;
    OffsetDateTime createdAt;
    OffsetDateTime updatedAt;
}
