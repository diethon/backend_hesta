package com.hesta.backend.service;

import com.hesta.backend.dto.request.GoogleLoginRequest;
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
import com.hesta.backend.security.GoogleAuthService;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserPreferenceRepository userPreferenceRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private GoogleAuthService googleAuthService;

    @InjectMocks
    private AuthServiceImpl authService;

    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;
    private GoogleLoginRequest googleLoginRequest;
    private User activeUser;

    @BeforeEach
    void setUp() {
        registerRequest = RegisterRequest.builder()
                .fullName("Nguyen Van A")
                .email("nguyenvana@example.com")
                .password("password123")
                .phoneNumber("0912345678")
                .build();

        loginRequest = LoginRequest.builder()
                .email("nguyenvana@example.com")
                .password("password123")
                .deviceId("DEV-01")
                .deviceType("WEB")
                .build();

        googleLoginRequest = GoogleLoginRequest.builder()
                .idToken("mock-google-token:googleuser@example.com:sub12345:Google User")
                .deviceId("DEV-01")
                .deviceType("WEB")
                .build();

        activeUser = User.builder()
                .id(UUID.randomUUID())
                .fullName("Nguyen Van A")
                .email("nguyenvana@example.com")
                .passwordHash("hashedPassword")
                .provider(AuthProvider.LOCAL)
                .platformRole(PlatformRole.USER)
                .status(AccountStatus.ACTIVE)
                .failedLoginAttempts((short) 0)
                .build();
    }

    @Test
    void register_Success() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashedPassword");
        when(userRepository.save(any(User.class))).thenReturn(activeUser);

        UserResponse response = authService.register(registerRequest);

        assertNotNull(response);
        assertEquals(registerRequest.getEmail(), response.getEmail());
        verify(userRepository, times(1)).save(any(User.class));
        verify(userPreferenceRepository, times(1)).save(any(UserPreference.class));
    }

    @Test
    void register_DuplicateEmail_ThrowsAppException() {
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        AppException exception = assertThrows(AppException.class, () -> authService.register(registerRequest));

        assertEquals(ErrorCode.USER_EXISTED, exception.getErrorCode());
    }

    @Test
    void login_Success() {
        when(userRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches(loginRequest.getPassword(), activeUser.getPasswordHash())).thenReturn(true);
        when(userRepository.save(any(User.class))).thenReturn(activeUser);
        when(jwtTokenProvider.generateAccessToken(any(User.class))).thenReturn("mockJwtToken");
        when(jwtTokenProvider.generateRefreshTokenString()).thenReturn("mockRefreshToken");
        when(jwtTokenProvider.hashToken(anyString())).thenReturn("hashedRefreshToken");
        when(jwtTokenProvider.getJwtExpirationInMs()).thenReturn(3600000L);
        when(jwtTokenProvider.getRefreshTokenExpirationInMs()).thenReturn(604800000L);

        AuthResponse response = authService.login(loginRequest);

        assertNotNull(response);
        assertEquals("mockJwtToken", response.getAccessToken());
        assertEquals("mockRefreshToken", response.getRefreshToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(activeUser.getEmail(), response.getUser().getEmail());

        verify(refreshTokenRepository, times(1)).save(any(RefreshToken.class));
    }

    @Test
    void login_InvalidPassword_IncrementsFailedAttempts() {
        when(userRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches(loginRequest.getPassword(), activeUser.getPasswordHash())).thenReturn(false);

        AppException exception = assertThrows(AppException.class, () -> authService.login(loginRequest));

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        assertEquals((short) 1, activeUser.getFailedLoginAttempts());
        verify(userRepository, times(1)).save(activeUser);
    }

    @Test
    void login_FifthFailedAttempt_LocksAccount() {
        activeUser.setFailedLoginAttempts((short) 4);
        when(userRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches(loginRequest.getPassword(), activeUser.getPasswordHash())).thenReturn(false);

        AppException exception = assertThrows(AppException.class, () -> authService.login(loginRequest));

        assertEquals(ErrorCode.ACCOUNT_LOCKED, exception.getErrorCode());
        assertEquals(AccountStatus.LOCKED, activeUser.getStatus());
        assertNotNull(activeUser.getLockedUntil());
        verify(userRepository, times(1)).save(activeUser);
    }

    @Test
    void loginWithGoogle_NewUser_Success() {
        GoogleAuthService.GoogleUserInfo googleUserInfo = GoogleAuthService.GoogleUserInfo.builder()
                .email("googleuser@example.com")
                .googleUid("sub12345")
                .fullName("Google User")
                .avatarUrl("http://avatar.url")
                .build();

        User newGoogleUser = User.builder()
                .id(UUID.randomUUID())
                .fullName(googleUserInfo.getFullName())
                .email(googleUserInfo.getEmail())
                .googleUid(googleUserInfo.getGoogleUid())
                .provider(AuthProvider.GOOGLE)
                .platformRole(PlatformRole.USER)
                .status(AccountStatus.ACTIVE)
                .build();

        when(googleAuthService.verifyGoogleToken(anyString())).thenReturn(googleUserInfo);
        when(userRepository.findByGoogleUid(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(newGoogleUser);

        when(jwtTokenProvider.generateAccessToken(any(User.class))).thenReturn("mockJwtToken");
        when(jwtTokenProvider.generateRefreshTokenString()).thenReturn("mockRefreshToken");
        when(jwtTokenProvider.hashToken(anyString())).thenReturn("hashedRefreshToken");

        AuthResponse response = authService.loginWithGoogle(googleLoginRequest);

        assertNotNull(response);
        assertEquals("mockJwtToken", response.getAccessToken());
        assertEquals("googleuser@example.com", response.getUser().getEmail());
        verify(userPreferenceRepository, times(1)).save(any(UserPreference.class));
    }
}
