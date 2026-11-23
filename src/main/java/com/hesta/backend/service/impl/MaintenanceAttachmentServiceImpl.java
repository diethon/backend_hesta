package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.MaintenanceAttachmentResponse;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.MaintenanceTicketAttachment;
import com.hesta.backend.entity.User;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.maintenance.assistant.ImageSanitizer;
import com.hesta.backend.repository.MaintenanceTicketAttachmentRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.CloudinaryService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MaintenanceAttachmentService;
import com.hesta.backend.service.MaintenanceRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MaintenanceAttachmentServiceImpl implements MaintenanceAttachmentService {

    public static final long MAX_SIZE_BYTES = 5L * 1024L * 1024L;

    private final HomeAuthorizationService homeAuthorizationService;
    private final UserRepository userRepository;
    private final MaintenanceTicketAttachmentRepository attachmentRepository;
    private final CloudinaryService cloudinaryService;
    private final MaintenanceRateLimiter rateLimiter;
    private final ImageSanitizer imageSanitizer;

    @Override
    @Transactional
    public MaintenanceAttachmentResponse upload(UUID userId, UUID homeId, MultipartFile file) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        Home home = homeAuthorizationService.requireAccess(userId, homeId);
        rateLimiter.checkAndConsumeUpload(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_REQUIRED);
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_TOO_LARGE);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_UPLOAD_FAILED);
        }
        if (bytes.length == 0) {
            throw new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_REQUIRED);
        }
        if (bytes.length > MAX_SIZE_BYTES) {
            throw new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_TOO_LARGE);
        }

        // Validate by magic bytes only; never trust client-supplied Content-Type.
        String detectedMime = detectImageMimeType(bytes);
        if (detectedMime == null) {
            throw new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_TYPE_INVALID);
        }

        // Loại bỏ EXIF/GPS ngay khi upload cho JPEG/PNG (chi phí thấp, giữ nguyên kích thước <= 2048px)
        byte[] bytesToStore = bytes;
        String mimeToStore = detectedMime;
        if (imageSanitizer != null) {
            var sanitized = imageSanitizer.sanitize(bytes, 2048);
            if (sanitized.isPresent()) {
                bytesToStore = sanitized.get().bytes();
                mimeToStore = sanitized.get().mimeType();
            }
        }

        CloudinaryService.UploadResult uploaded;
        try {
            uploaded = cloudinaryService.uploadMaintenanceAttachment(bytesToStore, mimeToStore);
        } catch (IOException e) {
            throw new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_UPLOAD_FAILED);
        }

        MaintenanceTicketAttachment saved = attachmentRepository.save(MaintenanceTicketAttachment.builder()
                .home(home)
                .uploadedBy(user)
                .storagePublicId(uploaded.publicId())
                .url(uploaded.url())
                .contentType(mimeToStore)
                .sizeBytes(bytesToStore.length)
                .build());

        log.info("Maintenance attachment {} uploaded in home {} by {} ({}, {} bytes)",
                saved.getId(), homeId, userId, detectedMime, bytes.length);
        return MaintenanceAttachmentResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AttachmentContent loadContent(UUID userId, boolean isAdmin, UUID homeId, UUID attachmentId) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (!isAdmin) {
            homeAuthorizationService.requireAccess(userId, homeId);
        }
        MaintenanceTicketAttachment attachment = attachmentRepository.findById(attachmentId)
                .filter(a -> a.getHome() != null && homeId.equals(a.getHome().getId()))
                .orElseThrow(() -> new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_NOT_FOUND));

        if (!isAdmin && attachment.getTicket() == null) {
            if (attachment.getUploadedBy() == null || !userId.equals(attachment.getUploadedBy().getId())) {
                throw new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_NOT_FOUND);
            }
        }
        if (isAdmin && attachment.getTicket() == null) {
            throw new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_NOT_FOUND);
        }

        try {
            byte[] bytes = cloudinaryService.downloadMaintenanceAttachment(
                    attachment.getStoragePublicId(), attachment.getContentType());
            return new AttachmentContent(bytes, attachment.getContentType());
        } catch (IOException e) {
            throw new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_UPLOAD_FAILED, "Không tải được nội dung ảnh đính kèm");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AttachmentContent loadOwnedForAiAnalysis(UUID userId, UUID homeId, UUID attachmentId) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        homeAuthorizationService.requireAccess(userId, homeId);
        MaintenanceTicketAttachment attachment = attachmentRepository.findById(attachmentId)
                .filter(a -> a.getHome() != null && homeId.equals(a.getHome().getId()))
                .filter(a -> a.getUploadedBy() != null && userId.equals(a.getUploadedBy().getId()))
                .orElseThrow(() -> new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_NOT_FOUND));
        try {
            byte[] bytes = cloudinaryService.downloadMaintenanceAttachment(
                    attachment.getStoragePublicId(), attachment.getContentType());
            return new AttachmentContent(bytes, attachment.getContentType());
        } catch (IOException e) {
            throw new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_UPLOAD_FAILED, "Không đọc được dữ liệu ảnh");
        }
    }

    @Override
    @Transactional
    public void deleteOwnedUnattached(UUID userId, UUID homeId, UUID attachmentId) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        homeAuthorizationService.requireAccess(userId, homeId);
        MaintenanceTicketAttachment attachment = attachmentRepository.findById(attachmentId)
                .filter(a -> a.getHome() != null && homeId.equals(a.getHome().getId()))
                .filter(a -> a.getUploadedBy() != null && userId.equals(a.getUploadedBy().getId()))
                .orElseThrow(() -> new AppException(ErrorCode.MAINTENANCE_ATTACHMENT_NOT_FOUND));
        if (attachment.getTicket() != null) {
            throw new AppException(ErrorCode.MAINTENANCE_INVALID_STATUS,
                    "Ảnh này đã gắn vào ticket bảo trì nên không thể xoá lẻ.");
        }
        cloudinaryService.deleteImage(attachment.getStoragePublicId());
        attachmentRepository.delete(attachment);
        log.info("Deleted unattached maintenance attachment {} (Cloudinary + DB) by {}", attachmentId, userId);
    }

    /**
     * Detect JPEG / PNG / WebP strictly from leading magic bytes.
     */
    public static String detectImageMimeType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A) {
            return "image/png";
        }
        // WebP: RIFF....WEBP
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
