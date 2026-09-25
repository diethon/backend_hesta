package com.hesta.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    // Scene management
    HOME_NOT_FOUND(1100, "Home not found", HttpStatus.NOT_FOUND),
    SCENE_NOT_FOUND(1101, "Scene not found", HttpStatus.NOT_FOUND),
    DEVICE_NOT_FOUND(1102, "Device not found", HttpStatus.NOT_FOUND),
    SCENE_NAME_INVALID(1103, "Scene name must be between 1 and 150 characters", HttpStatus.BAD_REQUEST),
    SCENE_DESCRIPTION_INVALID(1104, "Scene description must not exceed 2000 characters", HttpStatus.BAD_REQUEST),
    SCENE_NAME_ALREADY_EXISTS(1105, "A scene with this name already exists in the home", HttpStatus.CONFLICT),
    SCENE_ACTION_NOT_FOUND(1106, "Scene action not found", HttpStatus.NOT_FOUND),
    SCENE_ACTION_ORDER_INVALID(1107, "Scene action order must be between 0 and 32767", HttpStatus.BAD_REQUEST),
    SCENE_ACTION_ORDER_CONFLICT(1108, "Scene action order must be unique within the scene", HttpStatus.CONFLICT),
    SCENE_ACTION_CONFIGURATION_INVALID(1109, "Scene action and value must be valid", HttpStatus.BAD_REQUEST),
    SCENE_DEVICE_HOME_MISMATCH(1110, "Scene action device must belong to the scene's home", HttpStatus.BAD_REQUEST),
    SCENE_ENABLED_REQUIRED(1111, "Scene enabled status is required", HttpStatus.BAD_REQUEST),
    SCENE_ACTION_INVALID(1112, "Scene action is not supported", HttpStatus.BAD_REQUEST),
    SCENE_ACTION_VALUE_INVALID(1113, "Scene action value is invalid for the selected action", HttpStatus.BAD_REQUEST),
    SCENE_ACTIONS_INVALID(1114, "Scene actions must use unique contiguous order values starting at zero", HttpStatus.BAD_REQUEST),
    SCENE_REORDER_INVALID(1115, "Reorder payload must contain every scene action exactly once", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST(1116, "Request body is invalid", HttpStatus.BAD_REQUEST),
    SCENE_ICON_INVALID(1117, "Scene icon must not exceed 50 characters", HttpStatus.BAD_REQUEST),
    SCENE_DISABLED(1118, "Scene is disabled", HttpStatus.BAD_REQUEST),
    SCENE_SCHEDULE_INVALID(1119, "Scene schedule is invalid", HttpStatus.BAD_REQUEST),
    SCENE_SCHEDULE_NOT_FOUND(1120, "Scene schedule not found", HttpStatus.NOT_FOUND),
    SCENE_IN_USE(1121, "Scene is used by an automation rule", HttpStatus.CONFLICT),

    // Automation and behavior prototypes
    AUTOMATION_RULE_NOT_FOUND(1200, "Automation rule not found", HttpStatus.NOT_FOUND),
    AUTOMATION_NAME_INVALID(1201, "Automation rule name is invalid", HttpStatus.BAD_REQUEST),
    AUTOMATION_NAME_ALREADY_EXISTS(1202, "An automation rule with this name already exists", HttpStatus.CONFLICT),
    AUTOMATION_TRIGGER_INVALID(1203, "Automation trigger type is invalid", HttpStatus.BAD_REQUEST),
    AUTOMATION_ENABLED_REQUIRED(1204, "Automation enabled status is required", HttpStatus.BAD_REQUEST),
    AUTOMATION_CONDITION_INVALID(1205, "Automation condition is invalid", HttpStatus.BAD_REQUEST),
    AUTOMATION_ACTION_INVALID(1206, "Automation action is invalid", HttpStatus.BAD_REQUEST),
    AUTOMATION_DEVICE_HOME_MISMATCH(1207, "Automation device must belong to the rule home", HttpStatus.BAD_REQUEST),
    AUTOMATION_EVENT_INVALID(1208, "Automation event is invalid", HttpStatus.BAD_REQUEST),
    BEHAVIOR_DATASET_INVALID(1300, "Behavior dataset request is invalid", HttpStatus.BAD_REQUEST),
    BEHAVIOR_DEVICE_REQUIRED(1301, "At least one device is required to generate behavior data", HttpStatus.BAD_REQUEST),
    RECOMMENDATION_NOT_FOUND(1302, "Recommendation not found", HttpStatus.NOT_FOUND),
    RECOMMENDATION_ALREADY_RESOLVED(1303, "Recommendation is already resolved", HttpStatus.CONFLICT),

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
    GOOGLE_TOKEN_REQUIRED(1023, "Google ID Token không được để trống", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String message;
    private final HttpStatus statusCode; // Trạng thái HTTP tương ứng

    ErrorCode(int code, String message, HttpStatus statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}
