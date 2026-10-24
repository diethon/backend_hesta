package com.hesta.backend.controller;

import com.hesta.backend.dto.request.AdminCreateUserRequest;
import com.hesta.backend.dto.request.AdminResetPasswordRequest;
import com.hesta.backend.dto.request.AdminUpdateUserRequest;
import com.hesta.backend.dto.request.AdminUpdateUserStatusRequest;
import com.hesta.backend.dto.response.AdminUserResponse;
import com.hesta.backend.dto.response.AdminUserStatsResponse;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import com.hesta.backend.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    AdminUserService adminUserService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminUserResponse>>> getUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) PlatformRole role,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(required = false) AuthProvider provider,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction
    ) {
        Sort sort = "asc".equalsIgnoreCase(direction) ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        PageResponse<AdminUserResponse> result = adminUserService.getUsers(search, role, status, provider, pageable);

        return ResponseEntity.ok(ApiResponse.<PageResponse<AdminUserResponse>>builder()
                .code(1000)
                .message("Lấy danh sách người dùng thành công")
                .result(result)
                .build());
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<AdminUserStatsResponse>> getUserStats() {
        AdminUserStatsResponse result = adminUserService.getUserStats();

        return ResponseEntity.ok(ApiResponse.<AdminUserStatsResponse>builder()
                .code(1000)
                .message("Lấy thống kê người dùng thành công")
                .result(result)
                .build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminUserResponse>> getUserById(@PathVariable UUID id) {
        AdminUserResponse result = adminUserService.getUserById(id);

        return ResponseEntity.ok(ApiResponse.<AdminUserResponse>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AdminUserResponse>> createUser(
            @Valid @RequestBody AdminCreateUserRequest request) {
        AdminUserResponse result = adminUserService.createUser(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<AdminUserResponse>builder()
                        .code(1000)
                        .message("Tạo tài khoản người dùng thành công")
                        .result(result)
                        .build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminUserResponse>> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody AdminUpdateUserRequest request) {
        AdminUserResponse result = adminUserService.updateUser(id, request);

        return ResponseEntity.ok(ApiResponse.<AdminUserResponse>builder()
                .code(1000)
                .message("Cập nhật thông tin người dùng thành công")
                .result(result)
                .build());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<AdminUserResponse>> updateUserStatus(
            @PathVariable UUID id,
            @Valid @RequestBody AdminUpdateUserStatusRequest request) {
        AdminUserResponse result = adminUserService.updateUserStatus(id, request.getStatus());

        return ResponseEntity.ok(ApiResponse.<AdminUserResponse>builder()
                .code(1000)
                .message("Cập nhật trạng thái tài khoản thành công")
                .result(result)
                .build());
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<ApiResponse<AdminUserResponse>> unlockUser(@PathVariable UUID id) {
        AdminUserResponse result = adminUserService.unlockUser(id);

        return ResponseEntity.ok(ApiResponse.<AdminUserResponse>builder()
                .code(1000)
                .message("Mở khóa tài khoản thành công")
                .result(result)
                .build());
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody AdminResetPasswordRequest request) {
        adminUserService.resetPassword(id, request.getNewPassword());

        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .code(1000)
                .message("Đặt lại mật khẩu người dùng thành công")
                .build());
    }
}
