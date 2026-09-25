package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.NotificationPageResponse;
import com.hesta.backend.dto.response.NotificationReadAllResponse;
import com.hesta.backend.dto.response.NotificationResponse;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notifications;

    @GetMapping
    public ResponseEntity<ApiResponse<NotificationPageResponse>> list(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(required = false) UUID homeId,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(required = false) NotificationPriority priority,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(notifications.list(user.getId(), homeId, isRead, type, priority, page, size));
    }

    @GetMapping("/{notificationId}")
    public ResponseEntity<ApiResponse<NotificationResponse>> get(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID notificationId) {
        return ok(notifications.get(user.getId(), notificationId));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markRead(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable UUID notificationId) {
        return ok(notifications.markRead(user.getId(), notificationId));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<NotificationReadAllResponse>> markAllRead(
            @AuthenticationPrincipal CustomUserDetails user, @RequestParam(required = false) UUID homeId) {
        return ok(notifications.markAllRead(user.getId(), homeId));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T result) {
        return ResponseEntity.ok(ApiResponse.<T>builder().code(1000).result(result).build());
    }
}
