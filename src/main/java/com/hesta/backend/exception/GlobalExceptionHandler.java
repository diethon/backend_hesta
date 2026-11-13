package com.hesta.backend.exception;

import com.hesta.backend.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 1. Bắt các lỗi nghiệp vụ mà ta chủ động ném ra (throw new AppException(...))
     */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Object>> handleAppException(AppException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        String message = (exception.getMessage() != null && !exception.getMessage().isBlank())
                ? exception.getMessage()
                : errorCode.getMessage();
        
        ApiResponse<Object> apiResponse = ApiResponse.<Object>builder()
                .code(errorCode.getCode())
                .message(message)
                .build();
                
        return ResponseEntity.status(errorCode.getStatusCode()).body(apiResponse);
    }

    /**
     * 2. Bắt các lỗi Validation (VD: khi dùng @Valid, @NotNull, @Size,...)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidationException(MethodArgumentNotValidException exception) {
        // Lấy key lỗi đã cấu hình trong annotation (Ví dụ: @NotBlank(message = "USER_NOT_FOUND"))
        String enumKey = exception.getFieldError() != null ? exception.getFieldError().getDefaultMessage() : "INVALID_KEY";
        
        ErrorCode errorCode;
        try {
            errorCode = ErrorCode.valueOf(enumKey);
        } catch (IllegalArgumentException e) {
            errorCode = ErrorCode.INVALID_KEY;
        }

        ApiResponse<Object> apiResponse = ApiResponse.<Object>builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .build();
                
        return ResponseEntity.status(errorCode.getStatusCode()).body(apiResponse);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        ApiResponse<Object> apiResponse = ApiResponse.builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .build();
        return ResponseEntity.status(errorCode.getStatusCode()).body(apiResponse);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException exception) {
        return errorResponse(ErrorCode.AVATAR_FILE_TOO_LARGE);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingServletRequestPartException(
            MissingServletRequestPartException exception) {
        return errorResponse(ErrorCode.AVATAR_FILE_REQUIRED);
    }

    /**
     * 3. Bắt mọi lỗi Exception khác chưa được xử lý ở trên (Fall back)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGeneralException(Exception exception) {
        // Spring's own client errors (unknown route 404, wrong method 405, missing param 400) keep their status.
        if (exception instanceof org.springframework.web.ErrorResponse springError
                && springError.getStatusCode().is4xxClientError()) {
            return clientError(springError.getStatusCode(), springError.getBody().getDetail());
        }
        if (exception instanceof org.springframework.beans.TypeMismatchException) {
            return clientError(org.springframework.http.HttpStatus.BAD_REQUEST, "Request parameter has an invalid format");
        }
        log.error("Uncaught Exception: ", exception);
        
        ApiResponse<Object> apiResponse = ApiResponse.<Object>builder()
                .code(ErrorCode.UNCATEGORIZED_EXCEPTION.getCode())
                .message(ErrorCode.UNCATEGORIZED_EXCEPTION.getMessage())
                .build();
                
        return ResponseEntity.status(ErrorCode.UNCATEGORIZED_EXCEPTION.getStatusCode()).body(apiResponse);
    }

    private ResponseEntity<ApiResponse<Object>> clientError(org.springframework.http.HttpStatusCode status, String detail) {
        ApiResponse<Object> apiResponse = ApiResponse.<Object>builder()
                .code(ErrorCode.INVALID_REQUEST.getCode())
                .message(detail != null ? detail : "Request is invalid")
                .build();
        return ResponseEntity.status(status).body(apiResponse);
    }

    private ResponseEntity<ApiResponse<Object>> errorResponse(ErrorCode errorCode) {
        ApiResponse<Object> apiResponse = ApiResponse.<Object>builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .build();
        return ResponseEntity.status(errorCode.getStatusCode()).body(apiResponse);
    }
}
