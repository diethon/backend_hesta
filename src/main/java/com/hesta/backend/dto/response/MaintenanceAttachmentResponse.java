package com.hesta.backend.dto.response;

import com.hesta.backend.entity.MaintenanceTicketAttachment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MaintenanceAttachmentResponse(
        UUID attachmentId,
        String url,
        String contentType,
        long sizeBytes,
        OffsetDateTime createdAt
) {
    public static MaintenanceAttachmentResponse from(MaintenanceTicketAttachment attachment) {
        String contentUrl = attachment.getHome() != null && attachment.getId() != null
                ? "/api/v1/homes/" + attachment.getHome().getId() + "/maintenance-attachments/" + attachment.getId() + "/content"
                : attachment.getUrl();
        return new MaintenanceAttachmentResponse(
                attachment.getId(),
                contentUrl,
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getCreatedAt()
        );
    }
}
