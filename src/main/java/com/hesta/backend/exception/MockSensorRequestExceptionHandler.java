package com.hesta.backend.exception;

import com.hesta.backend.controller.MockSensorController;
import com.hesta.backend.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Safe rejection logging for bodies that cannot be decoded; never logs the body or headers. */
@RestControllerAdvice(assignableTypes = MockSensorController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class MockSensorRequestExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException exception) {
        log.warn("Rejected mock sensor body: deviceId=unavailable, metricType=unavailable, observedAt=unavailable, reason=INVALID_REQUEST");
        return ResponseEntity.badRequest().body(ApiResponse.<Void>builder()
                .code(ErrorCode.INVALID_REQUEST.getCode()).message(ErrorCode.INVALID_REQUEST.getMessage()).build());
    }
}
