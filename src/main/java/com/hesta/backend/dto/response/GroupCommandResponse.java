package com.hesta.backend.dto.response;

import com.hesta.backend.dto.command.CommandResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupCommandResponse {

    private UUID groupId;
    private String action;
    private int totalDevices;
    private int successCount;
    private int failureCount;
    private List<CommandResult> results;
}
