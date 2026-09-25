package com.hesta.backend.dto.response;

import com.hesta.backend.dto.command.CommandResult;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class ManualCommandResponse {
    private CommandResult command;
    private OffsetDateTime overrideUntil;
}
