package com.hesta.backend.service;

import com.hesta.backend.dto.request.ChangePasswordRequest;
import com.hesta.backend.dto.request.UpdateProfileRequest;
import com.hesta.backend.dto.response.UserResponse;

import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

public interface UserService {
    UserResponse updateProfile(UUID userId, UpdateProfileRequest request);
    void changePassword(UUID userId, ChangePasswordRequest request);
    UserResponse uploadAvatar(UUID userId, MultipartFile file);
}
