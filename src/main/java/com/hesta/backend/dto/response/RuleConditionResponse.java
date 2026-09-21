package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class RuleConditionResponse {
    UUID id;
    UUID deviceId;
    String deviceName;
    String attribute;
    String operator;
    Object expectedValue;
    String logicalOperator;
    int order;
}
