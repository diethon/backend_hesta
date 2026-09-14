package com.hesta.backend.dto.command;

import lombok.Builder;
import lombok.Data;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class CommandResult {
    private String commandId;
    private boolean success;
    private String status; // e.g., SENT, ACKNOWLEDGED, FAILED
    private String message;
    private String errorCode;
    private Map<String, Object> acknowledgedState;
    private Long latencyMs;
}
