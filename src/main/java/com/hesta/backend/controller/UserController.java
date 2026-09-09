package com.hesta.backend.controller;

import com.hesta.backend.dto.request.ChangePasswordRequest;
import com.hesta.backend.dto.request.UpdateProfileRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.UserResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.UserService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserController {

    UserService userService;

    @PutMapping("/me/profile")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody UpdateProfileRequest request) {

        UserResponse userResponse = userService.updateProfile(userDetails.getId(), request);

        ApiResponse<UserResponse> apiResponse = ApiResponse.<UserResponse>builder()
                .code(1000)
                .message("Cập nhật thông tin thành công")
                .result(userResponse)
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request) {

        userService.changePassword(userDetails.getId(), request);

        ApiResponse<Void> apiResponse = ApiResponse.<Void>builder()
                .code(1000)
                .message("Đổi mật khẩu thành công")
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @PostMapping("/me/avatar")
    public ResponseEntity<ApiResponse<UserResponse>> uploadAvatar(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {

        UserResponse userResponse = userService.uploadAvatar(userDetails.getId(), file);

        ApiResponse<UserResponse> apiResponse = ApiResponse.<UserResponse>builder()
                .code(1000)
                .message("Cập nhật ảnh đại diện thành công")
                .result(userResponse)
                .build();

        return ResponseEntity.ok(apiResponse);
    }
}
