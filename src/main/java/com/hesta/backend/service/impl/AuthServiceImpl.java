package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.RegisterRequest;
import com.hesta.backend.dto.response.UserResponse;
import com.hesta.backend.entity.User;
import com.hesta.backend.entity.UserPreference;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.UserPreferenceRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.AuthService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthServiceImpl implements AuthService {

    UserRepository userRepository;
    UserPreferenceRepository userPreferenceRepository;
    PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        
        if (userRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.USER_EXISTED);
        }

        User user = User.builder()
                .fullName(request.getFullName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .phoneNumber(request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null)
                .provider(AuthProvider.LOCAL)
                .platformRole(PlatformRole.USER)
                .status(AccountStatus.ACTIVE)
                .failedLoginAttempts((short) 0)
                .build();

        User savedUser = userRepository.save(user);

        UserPreference userPreference = UserPreference.builder()
                .user(savedUser)
                .preferredTemperature(new BigDecimal("25.0"))
                .preferredBrightness((short) 80)
                .theme("system")
                .language("vi")
                .voiceFeedbackEnabled(true)
                .notifySecurity(true)
                .notifyAutomation(true)
                .notifySystem(true)
                .build();

        userPreferenceRepository.save(userPreference);

        return UserResponse.builder()
                .id(savedUser.getId())
                .fullName(savedUser.getFullName())
                .email(savedUser.getEmail())
                .phoneNumber(savedUser.getPhoneNumber())
                .avatarUrl(savedUser.getAvatarUrl())
                .provider(savedUser.getProvider())
                .platformRole(savedUser.getPlatformRole())
                .status(savedUser.getStatus())
                .createdAt(savedUser.getCreatedAt())
                .lastActiveAt(savedUser.getLastActiveAt())
                .build();
    }
}
