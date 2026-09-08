package com.hesta.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    // Các mã lỗi chung
    UNCATEGORIZED_EXCEPTION(9999, "Lỗi không xác định", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_KEY(1001, "Lỗi hệ thống: Message key không hợp lệ", HttpStatus.BAD_REQUEST),
    
    // Các mã lỗi nghiệp vụ
    USER_EXISTED(1002, "Tài khoản đã tồn tại", HttpStatus.BAD_REQUEST),
    USER_NOT_FOUND(1003, "Không tìm thấy tài khoản", HttpStatus.NOT_FOUND),
    UNAUTHENTICATED(1004, "Chưa xác thực hoặc token không hợp lệ", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(1005, "Bạn không có quyền truy cập chức năng này", HttpStatus.FORBIDDEN);

    private final int code;
    private final String message;
    private final HttpStatus statusCode; // Trạng thái HTTP tương ứng

    ErrorCode(int code, String message, HttpStatus statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}
