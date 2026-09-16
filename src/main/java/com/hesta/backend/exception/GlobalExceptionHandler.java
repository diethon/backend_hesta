package com.hesta.backend.exception;

import com.hesta.backend.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
        
        ApiResponse<Object> apiResponse = ApiResponse.<Object>builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
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

    /**
     * 3. Bắt mọi lỗi Exception khác chưa được xử lý ở trên (Fall back)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGeneralException(Exception exception) {
        log.error("Uncaught Exception: ", exception);
        
        ApiResponse<Object> apiResponse = ApiResponse.<Object>builder()
                .code(ErrorCode.UNCATEGORIZED_EXCEPTION.getCode())
                .message(ErrorCode.UNCATEGORIZED_EXCEPTION.getMessage())
                .build();
                
        return ResponseEntity.status(ErrorCode.UNCATEGORIZED_EXCEPTION.getStatusCode()).body(apiResponse);
    }
}
