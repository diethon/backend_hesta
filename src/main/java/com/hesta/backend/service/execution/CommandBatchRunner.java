package com.hesta.backend.service.execution;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.ExecutionStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.service.BehaviorEventRecorder;
import com.hesta.backend.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sends the device commands of one scene or automation run and writes each result into its JSON detail row.
 * Shared by scenes and automations so both behave the same.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommandBatchRunner {
    private final DeviceCommandService deviceCommandService;
    private final BehaviorEventRecorder behaviorEventRecorder;

    /** One command to send; {@code detail} is its row in the execution result and gets filled in. */
    public record PlannedCommand(Device device, String action, Map<String, Object> parameters, ObjectNode detail) {}

    /**
     * Different devices run in parallel; commands for the same device keep their order
     * (round k sends the k-th command of every device, then waits for all of them).
     *
     * @return how many commands succeeded
     */
    public int run(List<PlannedCommand> commands, StateChangeSource source) {
        Map<UUID, List<PlannedCommand>> byDevice = new LinkedHashMap<>();
        for (PlannedCommand command : commands) {
            byDevice.computeIfAbsent(command.device().getId(), ignored -> new ArrayList<>()).add(command);
        }
        int succeeded = 0;
        for (int round = 0; ; round++) {
            List<PlannedCommand> batch = new ArrayList<>();
            for (List<PlannedCommand> deviceCommands : byDevice.values()) {
                if (round < deviceCommands.size()) batch.add(deviceCommands.get(round));
            }
            if (batch.isEmpty()) return succeeded;
            List<CompletableFuture<CommandResult>> results = batch.stream().map(command -> send(command, source)).toList();
            for (int index = 0; index < batch.size(); index++) {
                if (record(batch.get(index), results.get(index), source)) succeeded++;
            }
        }
    }

    /** SKIPPED when nothing was tried, SUCCESS only when every result row succeeded. */
    public static ExecutionStatus status(int rows, int attempted, int succeeded) {
        if (attempted == 0) return ExecutionStatus.SKIPPED;
        if (succeeded == rows) return ExecutionStatus.SUCCESS;
        return succeeded == 0 ? ExecutionStatus.FAILED : ExecutionStatus.PARTIAL;
    }

    public static ObjectNode detail(UUID deviceId, String action) {
        ObjectNode detail = JsonNodeFactory.instance.objectNode();
        if (deviceId != null) detail.put("deviceId", deviceId.toString());
        detail.put("action", action);
        return detail;
    }

    /** Marks a row as not run, e.g. SKIPPED_OVERRIDE, SKIPPED_CONFLICT, PREVIEW. */
    public static ObjectNode skip(ObjectNode detail, String status) {
        detail.put("success", false);
        detail.put("status", status);
        return detail;
    }

    private CompletableFuture<CommandResult> send(PlannedCommand command, StateChangeSource source) {
        try {
            return deviceCommandService.sendCommand(command.device().getId(), command.action(), command.parameters(), source);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private boolean record(PlannedCommand command, CompletableFuture<CommandResult> future, StateChangeSource source) {
        ObjectNode detail = command.detail();
        CommandResult result;
        try {
            result = future.join();
        } catch (RuntimeException exception) {
            detail.put("message", "Device command failed");
            skip(detail, "FAILED");
            return false;
        }
        detail.put("success", result.isSuccess());
        detail.put("status", result.getStatus());
        if (result.getCommandId() != null) detail.put("commandId", result.getCommandId());
        if (result.getErrorCode() != null) detail.put("errorCode", result.getErrorCode());
        if (result.getMessage() != null) detail.put("message", result.getMessage());
        if (!result.isSuccess()) return false;
        try {
            behaviorEventRecorder.recordCommand(command.device(), command.action(), source, result);
        } catch (RuntimeException recordingFailure) {
            log.warn("Could not record command for device {}", command.device().getId(), recordingFailure);
        }
        return true;
    }
}
