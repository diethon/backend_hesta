package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.NotificationReadAllResponse;
import com.hesta.backend.dto.response.NotificationResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> list(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) UUID homeId,
            @RequestParam(name = "isRead", required = false) Boolean isRead,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(required = false) NotificationPriority priority,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponse<NotificationResponse> result = notificationService.list(
                userDetails.getId(), homeId, isRead, type, priority, page, size
        );
        return ResponseEntity.ok(ApiResponse.<PageResponse<NotificationResponse>>builder()
                .code(1000)
                .result(result)
                .build());
    }

    @GetMapping("/{notificationId}")
    public ResponseEntity<ApiResponse<NotificationResponse>> get(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID notificationId
    ) {
        return ResponseEntity.ok(ApiResponse.<NotificationResponse>builder()
                .code(1000)
                .result(notificationService.get(userDetails.getId(), notificationId))
                .build());
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID notificationId
    ) {
        return ResponseEntity.ok(ApiResponse.<NotificationResponse>builder()
                .code(1000)
                .message("Đã đánh dấu thông báo là đã đọc")
                .result(notificationService.markAsRead(userDetails.getId(), notificationId))
                .build());
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<NotificationReadAllResponse>> markAllAsRead(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) UUID homeId
    ) {
        return ResponseEntity.ok(ApiResponse.<NotificationReadAllResponse>builder()
                .code(1000)
                .message("Đã đánh dấu tất cả thông báo là đã đọc")
                .result(notificationService.markAllAsRead(userDetails.getId(), homeId))
                .build());
    }
}
