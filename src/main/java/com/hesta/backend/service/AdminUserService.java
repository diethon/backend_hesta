package com.hesta.backend.service;

import com.hesta.backend.dto.request.AdminCreateUserRequest;
import com.hesta.backend.dto.request.AdminUpdateUserRequest;
import com.hesta.backend.dto.response.AdminUserResponse;
import com.hesta.backend.dto.response.AdminUserStatsResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface AdminUserService {
    PageResponse<AdminUserResponse> getUsers(
            String search,
            PlatformRole role,
            AccountStatus status,
            AuthProvider provider,
            Pageable pageable
    );

    AdminUserStatsResponse getUserStats();

    AdminUserResponse getUserById(UUID id);

    AdminUserResponse createUser(AdminCreateUserRequest request);

    AdminUserResponse updateUser(UUID id, AdminUpdateUserRequest request);

    AdminUserResponse updateUserStatus(UUID id, AccountStatus status);

    AdminUserResponse unlockUser(UUID id);

    void resetPassword(UUID id, String newPassword);
}
