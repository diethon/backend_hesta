package com.hesta.backend.controller;

import com.hesta.backend.dto.request.GoogleLoginRequest;
import com.hesta.backend.dto.request.LoginRequest;
import com.hesta.backend.dto.request.RegisterRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.AuthResponse;
import com.hesta.backend.dto.response.UserResponse;
import com.hesta.backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthController {

    AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse userResponse = authService.register(request);

        ApiResponse<UserResponse> apiResponse = ApiResponse.<UserResponse>builder()
                .code(1000)
                .message("Đăng ký tài khoản thành công")
                .result(userResponse)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(apiResponse);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse authResponse = authService.login(request);

        ApiResponse<AuthResponse> apiResponse = ApiResponse.<AuthResponse>builder()
                .code(1000)
                .message("Đăng nhập thành công")
                .result(authResponse)
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthResponse>> loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        AuthResponse authResponse = authService.loginWithGoogle(request);

        ApiResponse<AuthResponse> apiResponse = ApiResponse.<AuthResponse>builder()
                .code(1000)
                .message("Đăng nhập Google thành công")
                .result(authResponse)
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody com.hesta.backend.dto.request.ForgotPasswordRequest request) {
        authService.forgotPassword(request);

        ApiResponse<Void> apiResponse = ApiResponse.<Void>builder()
                .code(1000)
                .message("Mã OTP khôi phục mật khẩu đã được gửi đến email của bạn")
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<Void>> verifyOtp(@Valid @RequestBody com.hesta.backend.dto.request.VerifyOtpRequest request) {
        authService.verifyOtp(request);

        ApiResponse<Void> apiResponse = ApiResponse.<Void>builder()
                .code(1000)
                .message("Xác thực OTP thành công.")
                .build();

        return ResponseEntity.ok(apiResponse);
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody com.hesta.backend.dto.request.ResetPasswordRequest request) {
        authService.resetPassword(request);

        ApiResponse<Void> apiResponse = ApiResponse.<Void>builder()
                .code(1000)
                .message("Khôi phục mật khẩu thành công. Vui lòng đăng nhập lại.")
                .build();

        return ResponseEntity.ok(apiResponse);
    }
}
