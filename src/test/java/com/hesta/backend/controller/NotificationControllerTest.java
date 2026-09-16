package com.hesta.backend.controller;

import com.hesta.backend.dto.response.NotificationReadAllResponse;
import com.hesta.backend.dto.response.NotificationResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.exception.GlobalExceptionHandler;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.JwtAuthenticationFilter;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private UUID userId;
    private UUID homeId;
    private CustomUserDetails userDetails;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        homeId = UUID.randomUUID();
        userDetails = new CustomUserDetails(
                userId,
                "user@example.com",
                "",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void list_withFilters_returnsTypedPaginatedEnvelope() throws Exception {
        NotificationResponse notification = notificationResponse(false);
        PageResponse<NotificationResponse> page = new PageResponse<>(
                List.of(notification), 0, 20, 1, 1, true
        );
        when(notificationService.list(
                userId, homeId, false, NotificationType.SECURITY, NotificationPriority.HIGH, 0, 20
        )).thenReturn(page);

        mockMvc.perform(get("/api/v1/notifications")
                        .with(authentication(authentication))
                        .param("homeId", homeId.toString())
                        .param("isRead", "false")
                        .param("type", "SECURITY")
                        .param("priority", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.content[0].id").value(notification.id().toString()))
                .andExpect(jsonPath("$.result.content[0].isRead").value(false))
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @Test
    void get_whenServiceRejectsCrossUserAccess_returnsNotFoundContract() throws Exception {
        UUID notificationId = UUID.randomUUID();
        when(notificationService.get(userId, notificationId))
                .thenThrow(new AppException(ErrorCode.NOTIFICATION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/notifications/{notificationId}", notificationId)
                        .with(authentication(authentication)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOTIFICATION_NOT_FOUND.getCode()));
    }

    @Test
    void markAsRead_usesAuthenticatedUserIdentity() throws Exception {
        NotificationResponse notification = notificationResponse(true);
        when(notificationService.markAsRead(userId, notification.id())).thenReturn(notification);

        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notification.id())
                        .with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.isRead").value(true));

        verify(notificationService).markAsRead(eq(userId), eq(notification.id()));
    }

    @Test
    void markAllAsRead_scopesOperationToAuthenticatedUserAndHome() throws Exception {
        when(notificationService.markAllAsRead(userId, homeId))
                .thenReturn(new NotificationReadAllResponse(4));

        mockMvc.perform(patch("/api/v1/notifications/read-all")
                        .with(authentication(authentication))
                        .param("homeId", homeId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.updatedCount").value(4));

        verify(notificationService).markAllAsRead(userId, homeId);
    }

    private NotificationResponse notificationResponse(boolean read) {
        return new NotificationResponse(
                UUID.randomUUID(),
                userId,
                homeId,
                NotificationType.SECURITY,
                "Security alert",
                "Motion detected",
                NotificationPriority.HIGH,
                read,
                OffsetDateTime.now()
        );
    }
}
