package com.hesta.backend.service;

import com.hesta.backend.dto.response.MaintenanceAttachmentResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface MaintenanceAttachmentService {

    record AttachmentContent(byte[] bytes, String contentType) {
    }

    MaintenanceAttachmentResponse upload(UUID userId, UUID homeId, MultipartFile file);

    AttachmentContent loadContent(UUID userId, boolean isAdmin, UUID homeId, UUID attachmentId);

    AttachmentContent loadOwnedForAiAnalysis(UUID userId, UUID homeId, UUID attachmentId);

    void deleteOwnedUnattached(UUID userId, UUID homeId, UUID attachmentId);
}
