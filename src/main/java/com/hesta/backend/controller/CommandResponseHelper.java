package com.hesta.backend.controller;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class CommandResponseHelper {

    private CommandResponseHelper() {
    }

    static ResponseEntity<ApiResponse<CommandResult>> toResponseEntity(CommandResult result, String successMessage) {
        if (result != null && result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.<CommandResult>builder()
                    .code(1000)
                    .message(successMessage)
                    .result(result)
                    .build());
        }

        String errCode = result != null ? result.getErrorCode() : null;
        String statusStr = result != null ? result.getStatus() : null;

        HttpStatus httpStatus;
        int apiCode;
        if ("DEVICE_OFFLINE".equals(errCode)) {
            httpStatus = HttpStatus.CONFLICT;
            apiCode = ErrorCode.DEVICE_OFFLINE.getCode();
        } else if ("CIRCUIT_BREAKER_OPEN".equals(errCode)
                || "COMMAND_IN_PROGRESS_DUPLICATE_REJECTED".equals(errCode)) {
            httpStatus = HttpStatus.CONFLICT;
            apiCode = ErrorCode.MQTT_PUBLISH_FAILED.getCode();
        } else if ("GATEWAY_DISCONNECTED".equals(errCode)) {
            httpStatus = HttpStatus.SERVICE_UNAVAILABLE;
            apiCode = ErrorCode.MQTT_PUBLISH_FAILED.getCode();
        } else if ("TIMEOUT_NO_ACK".equals(errCode) || "TIMEOUT".equalsIgnoreCase(statusStr)) {
            httpStatus = HttpStatus.GATEWAY_TIMEOUT;
            apiCode = ErrorCode.MQTT_PUBLISH_FAILED.getCode();
        } else if ("INVALID_ACTION".equals(errCode) || "MISSING_PARAMETERS".equals(errCode)) {
            httpStatus = HttpStatus.BAD_REQUEST;
            apiCode = ErrorCode.INVALID_DEVICE_ACTION.getCode();
        } else {
            httpStatus = HttpStatus.BAD_REQUEST;
            apiCode = ErrorCode.MQTT_PUBLISH_FAILED.getCode();
        }

        String msg = result != null && result.getMessage() != null && !result.getMessage().isBlank()
                ? result.getMessage()
                : "Không thể thực thi lệnh điều khiển thiết bị";

        return ResponseEntity.status(httpStatus)
                .body(ApiResponse.<CommandResult>builder()
                        .code(apiCode)
                        .message(msg)
                        .result(result)
                        .build());
    }
}
