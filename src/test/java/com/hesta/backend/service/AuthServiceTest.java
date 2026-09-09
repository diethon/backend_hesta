package com.hesta.backend.service;

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
import com.hesta.backend.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
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
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthServiceImpl authService;

    private RegisterRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = RegisterRequest.builder()
                .fullName("Nguyen Van A")
                .email("nguyenvana@example.com")
                .password("password123")
                .phoneNumber("0912345678")
                .build();
    }

    @Test
    void register_Success() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashedPassword");

        User savedUser = User.builder()
                .id(UUID.randomUUID())
                .fullName(validRequest.getFullName())
                .email(validRequest.getEmail())
                .passwordHash("hashedPassword")
                .phoneNumber(validRequest.getPhoneNumber())
                .provider(AuthProvider.LOCAL)
                .platformRole(PlatformRole.USER)
                .status(AccountStatus.ACTIVE)
                .createdAt(OffsetDateTime.now())
                .build();

        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        UserResponse response = authService.register(validRequest);

        assertNotNull(response);
        assertEquals(validRequest.getEmail(), response.getEmail());
        assertEquals(validRequest.getFullName(), response.getFullName());
        assertEquals(PlatformRole.USER, response.getPlatformRole());
        assertEquals(AccountStatus.ACTIVE, response.getStatus());

        verify(userRepository, times(1)).save(any(User.class));
        verify(userPreferenceRepository, times(1)).save(any(UserPreference.class));
    }

    @Test
    void register_DuplicateEmail_ThrowsAppException() {
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        AppException exception = assertThrows(AppException.class, () -> authService.register(validRequest));

        assertEquals(ErrorCode.USER_EXISTED, exception.getErrorCode());
        verify(userRepository, never()).save(any(User.class));
        verify(userPreferenceRepository, never()).save(any(UserPreference.class));
    }
}
