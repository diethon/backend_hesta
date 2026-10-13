package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.ChangePasswordRequest;
import com.hesta.backend.dto.request.UpdateProfileRequest;
import com.hesta.backend.dto.response.UserResponse;
import com.hesta.backend.entity.User;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.CloudinaryService;
import com.hesta.backend.service.UserService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserServiceImpl implements UserService {

    private static final long MAX_AVATAR_SIZE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_AVATAR_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif"
    );

    UserRepository userRepository;
    PasswordEncoder passwordEncoder;
    CloudinaryService cloudinaryService;

    @Override
    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        user.setFullName(request.getFullName().trim());
        if (request.getPhoneNumber() != null) {
            user.setPhoneNumber(request.getPhoneNumber().trim());
        }

        userRepository.save(user);

        return UserResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .avatarUrl(user.getAvatarUrl())
                .provider(user.getProvider())
                .platformRole(user.getPlatformRole())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .lastActiveAt(user.getLastActiveAt())
                .build();
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        // Note: For OAuth2 users who don't have a password yet, we might allow them to set one.
        // But for now, we assume they need to have a password to change it.
        if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS); // Customize this error if needed
        }

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    @Override
    @Transactional
    public UserResponse uploadAvatar(UUID userId, MultipartFile file) {
        validateAvatar(file);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        try {
            String avatarUrl = cloudinaryService.uploadAvatar(file);
            user.setAvatarUrl(avatarUrl);
            userRepository.save(user);

            return UserResponse.builder()
                    .id(user.getId())
                    .fullName(user.getFullName())
                    .email(user.getEmail())
                    .phoneNumber(user.getPhoneNumber())
                    .avatarUrl(user.getAvatarUrl())
                    .provider(user.getProvider())
                    .platformRole(user.getPlatformRole())
                    .status(user.getStatus())
                    .createdAt(user.getCreatedAt())
                    .lastActiveAt(user.getLastActiveAt())
                    .build();
        } catch (IOException e) {
            throw new AppException(ErrorCode.AVATAR_UPLOAD_FAILED);
        }
    }

    private void validateAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.AVATAR_FILE_REQUIRED);
        }
        if (file.getSize() > MAX_AVATAR_SIZE_BYTES) {
            throw new AppException(ErrorCode.AVATAR_FILE_TOO_LARGE);
        }

        String contentType = file.getContentType();
        if (contentType == null
                || !ALLOWED_AVATAR_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new AppException(ErrorCode.AVATAR_FILE_TYPE_INVALID);
        }
    }
}
