package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.AdminCreateUserRequest;
import com.hesta.backend.dto.request.AdminResetPasswordRequest;
import com.hesta.backend.dto.request.AdminUpdateUserStatusRequest;
import com.hesta.backend.dto.response.AdminUserResponse;
import com.hesta.backend.dto.response.AdminUserStatsResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdminUserService adminUserService;

    private UUID adminId;
    private UUID normalUserId;
    private UsernamePasswordAuthenticationToken adminAuth;
    private UsernamePasswordAuthenticationToken userAuth;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        normalUserId = UUID.randomUUID();

        CustomUserDetails adminUserDetails = new CustomUserDetails(
                adminId,
                "admin@hesta.vn",
                "",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        adminAuth = new UsernamePasswordAuthenticationToken(adminUserDetails, null, adminUserDetails.getAuthorities());

        CustomUserDetails normalUserDetails = new CustomUserDetails(
                normalUserId,
                "user@hesta.vn",
                "",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        userAuth = new UsernamePasswordAuthenticationToken(normalUserDetails, null, normalUserDetails.getAuthorities());
    }

    @Test
    @DisplayName("Admin can get users list successfully")
    void getUsers_asAdmin_returnsOk() throws Exception {
        UUID targetId = UUID.randomUUID();
        AdminUserResponse userItem = AdminUserResponse.builder()
                .id(targetId)
                .fullName("Test User")
                .email("test@hesta.vn")
                .provider(AuthProvider.LOCAL)
                .platformRole(PlatformRole.USER)
                .status(AccountStatus.ACTIVE)
                .createdAt(OffsetDateTime.now())
                .homeCount(1)
                .build();

        PageResponse<AdminUserResponse> pageResponse = new PageResponse<>(
                List.of(userItem), 0, 10, 1, 1, true
        );

        when(adminUserService.getUsers(any(), any(), any(), any(), any())).thenReturn(pageResponse);

        mockMvc.perform(get("/api/v1/admin/users")
                        .with(authentication(adminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.content[0].fullName").value("Test User"))
                .andExpect(jsonPath("$.result.content[0].email").value("test@hesta.vn"));
    }

    @Test
    @DisplayName("Non-admin user cannot get admin users list")
    void getUsers_asNormalUser_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(authentication(userAuth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin can get user stats")
    void getUserStats_asAdmin_returnsOk() throws Exception {
        AdminUserStatsResponse statsResponse = AdminUserStatsResponse.builder()
                .totalUsers(10)
                .activeUsers(8)
                .lockedUsers(1)
                .disabledUsers(1)
                .adminUsers(2)
                .standardUsers(8)
                .localUsers(7)
                .googleUsers(3)
                .registrationTrend(List.of(
                        new AdminUserStatsResponse.RegistrationTrendPoint("T05/2026", 4),
                        new AdminUserStatsResponse.RegistrationTrendPoint("T06/2026", 6)
                ))
                .build();

        when(adminUserService.getUserStats()).thenReturn(statsResponse);

        mockMvc.perform(get("/api/v1/admin/users/stats")
                        .with(authentication(adminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.totalUsers").value(10))
                .andExpect(jsonPath("$.result.activeUsers").value(8));
    }

    @Test
    @DisplayName("Admin can create new user")
    void createUser_asAdmin_returnsCreated() throws Exception {
        AdminCreateUserRequest req = AdminCreateUserRequest.builder()
                .fullName("New Person")
                .email("new@hesta.vn")
                .password("password123")
                .phoneNumber("0912345678")
                .platformRole(PlatformRole.USER)
                .build();

        UUID createdId = UUID.randomUUID();
        AdminUserResponse response = AdminUserResponse.builder()
                .id(createdId)
                .fullName("New Person")
                .email("new@hesta.vn")
                .platformRole(PlatformRole.USER)
                .status(AccountStatus.ACTIVE)
                .build();

        when(adminUserService.createUser(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/users")
                        .with(authentication(adminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.fullName").value("New Person"));
    }

    @Test
    @DisplayName("Admin can update user status")
    void updateUserStatus_asAdmin_returnsOk() throws Exception {
        UUID targetId = UUID.randomUUID();
        AdminUpdateUserStatusRequest req = AdminUpdateUserStatusRequest.builder()
                .status(AccountStatus.LOCKED)
                .build();

        AdminUserResponse response = AdminUserResponse.builder()
                .id(targetId)
                .fullName("Target User")
                .email("target@hesta.vn")
                .status(AccountStatus.LOCKED)
                .build();

        when(adminUserService.updateUserStatus(eq(targetId), eq(AccountStatus.LOCKED))).thenReturn(response);

        mockMvc.perform(patch("/api/v1/admin/users/" + targetId + "/status")
                        .with(authentication(adminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.status").value("LOCKED"));
    }

    @Test
    @DisplayName("Admin can unlock user account")
    void unlockUser_asAdmin_returnsOk() throws Exception {
        UUID targetId = UUID.randomUUID();
        AdminUserResponse response = AdminUserResponse.builder()
                .id(targetId)
                .fullName("Target User")
                .email("target@hesta.vn")
                .status(AccountStatus.ACTIVE)
                .failedLoginAttempts((short) 0)
                .build();

        when(adminUserService.unlockUser(eq(targetId))).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/users/" + targetId + "/unlock")
                        .with(authentication(adminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("Admin can reset user password")
    void resetPassword_asAdmin_returnsOk() throws Exception {
        UUID targetId = UUID.randomUUID();
        AdminResetPasswordRequest req = AdminResetPasswordRequest.builder()
                .newPassword("newSecurePass123")
                .build();

        doNothing().when(adminUserService).resetPassword(eq(targetId), any());

        mockMvc.perform(post("/api/v1/admin/users/" + targetId + "/reset-password")
                        .with(authentication(adminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
    }
}
