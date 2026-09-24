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
    UNAUTHORIZED(1005, "Bạn không có quyền truy cập chức năng này", HttpStatus.FORBIDDEN),

    // Các mã lỗi validation & auth bổ sung
    INVALID_CREDENTIALS(1006, "Email hoặc mật khẩu không chính xác", HttpStatus.BAD_REQUEST),
    ACCOUNT_LOCKED(1007, "Tài khoản tạm thời bị khóa do đăng nhập sai nhiều lần", HttpStatus.FORBIDDEN),
    ACCOUNT_DISABLED(1008, "Tài khoản đã bị vô hiệu hóa", HttpStatus.FORBIDDEN),
    GOOGLE_AUTH_FAILED(1013, "Xác thực Google OAuth thất bại", HttpStatus.UNAUTHORIZED),
    FULL_NAME_REQUIRED(1017, "Họ và tên không được để trống", HttpStatus.BAD_REQUEST),
    FULL_NAME_INVALID(1018, "Họ và tên quá dài (tối đa 150 ký tự)", HttpStatus.BAD_REQUEST),
    EMAIL_REQUIRED(1019, "Email không được để trống", HttpStatus.BAD_REQUEST),
    INVALID_EMAIL(1020, "Định dạng email không hợp lệ", HttpStatus.BAD_REQUEST),
    PASSWORD_REQUIRED(1021, "Mật khẩu không được để trống", HttpStatus.BAD_REQUEST),
    PASSWORD_TOO_SHORT(1022, "Mật khẩu phải có ít nhất 8 ký tự", HttpStatus.BAD_REQUEST),
    GOOGLE_TOKEN_REQUIRED(1023, "Google ID Token không được để trống", HttpStatus.BAD_REQUEST),

    // Các mã lỗi điều khiển thiết bị (Device & Command)
    DEVICE_NOT_FOUND(1030, "Không tìm thấy thiết bị", HttpStatus.NOT_FOUND),
    INVALID_DEVICE_ACTION(1031, "Hành động điều khiển không hợp lệ", HttpStatus.BAD_REQUEST),
    INVALID_RGB_VALUE(1032, "Giá trị RGB không hợp lệ (phải từ 0 đến 255)", HttpStatus.BAD_REQUEST),
    INVALID_BRIGHTNESS_VALUE(1033, "Giá trị độ sáng không hợp lệ (phải từ 0 đến 100)", HttpStatus.BAD_REQUEST),
    DEVICE_MQTT_TOPIC_MISSING(1034, "Thiết bị chưa được cấu hình MQTT topic", HttpStatus.BAD_REQUEST),
    MQTT_PUBLISH_FAILED(1035, "Không thể gửi lệnh điều khiển tới thiết bị qua MQTT broker", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus statusCode; // Trạng thái HTTP tương ứng

    ErrorCode(int code, String message, HttpStatus statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}
