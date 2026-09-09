package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.GoogleLoginRequest;
import com.hesta.backend.dto.request.LoginRequest;
import com.hesta.backend.dto.request.RegisterRequest;
import com.hesta.backend.dto.response.AuthResponse;
import com.hesta.backend.dto.response.UserResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.exception.GlobalExceptionHandler;
import com.hesta.backend.security.JwtAuthenticationFilter;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void register_ValidPayload_Returns201Created() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .fullName("Test User")
                .email("testuser@example.com")
                .password("password123")
                .phoneNumber("0900000000")
                .build();

        UserResponse userResponse = UserResponse.builder()
                .id(UUID.randomUUID())
                .fullName("Test User")
                .email("testuser@example.com")
                .provider(AuthProvider.LOCAL)
                .platformRole(PlatformRole.USER)
                .status(AccountStatus.ACTIVE)
                .build();

        when(authService.register(any(RegisterRequest.class))).thenReturn(userResponse);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.email").value("testuser@example.com"))
                .andExpect(jsonPath("$.result.fullName").value("Test User"));
    }

    @Test
    void login_ValidCredentials_Returns200OkWithTokens() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("testuser@example.com")
                .password("password123")
                .build();

        AuthResponse authResponse = AuthResponse.builder()
                .accessToken("mockAccessToken")
                .refreshToken("mockRefreshToken")
                .tokenType("Bearer")
                .expiresIn(3600)
                .user(UserResponse.builder()
                        .id(UUID.randomUUID())
                        .email("testuser@example.com")
                        .fullName("Test User")
                        .platformRole(PlatformRole.USER)
                        .status(AccountStatus.ACTIVE)
                        .build())
                .build();

        when(authService.login(any(LoginRequest.class))).thenReturn(authResponse);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.accessToken").value("mockAccessToken"))
                .andExpect(jsonPath("$.result.refreshToken").value("mockRefreshToken"));
    }

    @Test
    void loginWithGoogle_ValidToken_Returns200Ok() throws Exception {
        GoogleLoginRequest request = GoogleLoginRequest.builder()
                .idToken("mock-google-token:googleuser@example.com:sub123")
                .build();

        AuthResponse authResponse = AuthResponse.builder()
                .accessToken("mockGoogleJwtToken")
                .refreshToken("mockGoogleRefreshToken")
                .tokenType("Bearer")
                .expiresIn(3600)
                .user(UserResponse.builder()
                        .id(UUID.randomUUID())
                        .email("googleuser@example.com")
                        .fullName("Google User")
                        .provider(AuthProvider.GOOGLE)
                        .platformRole(PlatformRole.USER)
                        .status(AccountStatus.ACTIVE)
                        .build())
                .build();

        when(authService.loginWithGoogle(any(GoogleLoginRequest.class))).thenReturn(authResponse);

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.accessToken").value("mockGoogleJwtToken"))
                .andExpect(jsonPath("$.result.user.provider").value("GOOGLE"));
    }
}
