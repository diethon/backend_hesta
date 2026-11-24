package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.MaintenanceAttachmentResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.policy.AccessPolicyType;
import com.hesta.backend.security.policy.HomeAccessPolicy;
import com.hesta.backend.service.MaintenanceAttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/maintenance-attachments")
@RequiredArgsConstructor
public class MaintenanceAttachmentController {

    private final MaintenanceAttachmentService attachmentService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Upload an image attachment before creating a maintenance ticket")
    public ResponseEntity<ApiResponse<MaintenanceAttachmentResponse>> upload(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @RequestPart("file") MultipartFile file) {
        UUID userId = user != null ? user.getId() : null;
        MaintenanceAttachmentResponse result = attachmentService.upload(userId, homeId, file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<MaintenanceAttachmentResponse>builder()
                        .code(1000)
                        .message("Đã tải ảnh đính kèm lên thành công")
                        .result(result)
                        .build());
    }

    @GetMapping("/{attachmentId}/content")
    @HomeAccessPolicy(value = AccessPolicyType.AUTHENTICATED, description = "Authenticated home member or platform admin reads maintenance attachment content")
    public ResponseEntity<byte[]> getContent(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID attachmentId) {
        UUID userId = user != null ? user.getId() : null;
        boolean isAdmin = user != null && user.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        MaintenanceAttachmentService.AttachmentContent content =
                attachmentService.loadContent(userId, isAdmin, homeId, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(content.bytes());
    }

    @DeleteMapping("/{attachmentId}")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Delete user's own unattached maintenance image from DB and Cloudinary")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID attachmentId) {
        UUID userId = user != null ? user.getId() : null;
        attachmentService.deleteOwnedUnattached(userId, homeId, attachmentId);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .code(1000)
                .message("Đã xoá ảnh đính kèm")
                .build());
    }
}
