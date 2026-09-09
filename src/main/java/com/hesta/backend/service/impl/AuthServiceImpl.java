package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.LoginRequest;
import com.hesta.backend.dto.request.RegisterRequest;
import com.hesta.backend.dto.response.AuthResponse;
import com.hesta.backend.dto.response.UserResponse;
import com.hesta.backend.entity.RefreshToken;
import com.hesta.backend.entity.User;
import com.hesta.backend.entity.UserPreference;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.RefreshTokenRepository;
import com.hesta.backend.repository.UserPreferenceRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.service.AuthService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthServiceImpl implements AuthService {

    UserRepository userRepository;
    UserPreferenceRepository userPreferenceRepository;
    RefreshTokenRepository refreshTokenRepository;
    PasswordEncoder passwordEncoder;
    JwtTokenProvider jwtTokenProvider;

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

        return mapToUserResponse(savedUser);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_CREDENTIALS));

        if (user.getStatus() == AccountStatus.DISABLED) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED);
        }

        if (user.getStatus() == AccountStatus.LOCKED) {
            if (user.getLockedUntil() != null && OffsetDateTime.now().isBefore(user.getLockedUntil())) {
                throw new AppException(ErrorCode.ACCOUNT_LOCKED);
            }
            // Unlock account if lockout expired
            user.setStatus(AccountStatus.ACTIVE);
            user.setFailedLoginAttempts((short) 0);
            user.setLockedUntil(null);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            short attempts = (short) (user.getFailedLoginAttempts() + 1);
            user.setFailedLoginAttempts(attempts);

            if (attempts >= 5) {
                user.setStatus(AccountStatus.LOCKED);
                user.setLockedUntil(OffsetDateTime.now().plusMinutes(15));
                userRepository.save(user);
                throw new AppException(ErrorCode.ACCOUNT_LOCKED);
            }

            userRepository.save(user);
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

        // Login success: reset failed attempts, update lastActiveAt
        user.setFailedLoginAttempts((short) 0);
        user.setLockedUntil(null);
        user.setStatus(AccountStatus.ACTIVE);
        user.setLastActiveAt(OffsetDateTime.now());
        User updatedUser = userRepository.save(user);

        // Generate tokens
        String accessToken = jwtTokenProvider.generateAccessToken(updatedUser);
        String rawRefreshToken = jwtTokenProvider.generateRefreshTokenString();
        String tokenHash = jwtTokenProvider.hashToken(rawRefreshToken);

        String deviceId = request.getDeviceId() != null && !request.getDeviceId().isBlank()
                ? request.getDeviceId().trim() : "WEB_DEFAULT";
        String deviceType = request.getDeviceType() != null && !request.getDeviceType().isBlank()
                ? request.getDeviceType().trim() : "BROWSER";

        RefreshToken refreshTokenEntity = RefreshToken.builder()
                .user(updatedUser)
                .deviceId(deviceId)
                .deviceType(deviceType)
                .tokenHash(tokenHash)
                .isRevoked(false)
                .expiresAt(OffsetDateTime.now().plusSeconds(jwtTokenProvider.getRefreshTokenExpirationInMs() / 1000))
                .build();

        refreshTokenRepository.save(refreshTokenEntity);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getJwtExpirationInMs() / 1000)
                .user(mapToUserResponse(updatedUser))
                .build();
    }

    private UserResponse mapToUserResponse(User user) {
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
}
