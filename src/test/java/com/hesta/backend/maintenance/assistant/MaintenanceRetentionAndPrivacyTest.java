package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.entity.*;
import com.hesta.backend.enums.MaintenanceChatStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.job.MaintenanceAttachmentCleanupJob;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.CloudinaryService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MaintenanceRateLimiter;
import com.hesta.backend.service.impl.MaintenanceAttachmentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaintenanceRetentionAndPrivacyTest {

    @Mock private MaintenanceTicketAttachmentRepository attachmentRepository;
    @Mock private MaintenanceChatConversationRepository conversationRepository;
    @Mock private MaintenanceChatMessageRepository messageRepository;
    @Mock private CloudinaryService cloudinaryService;
    @Mock private HomeAuthorizationService homeAuthorizationService;
    @Mock private UserRepository userRepository;
    @Mock private MaintenanceRateLimiter rateLimiter;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-23T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void retentionJobPurgesOrphanImagesOnCloudinaryAndOldFinishedMessagesOnly() {
        MaintenanceRetentionProperties props = new MaintenanceRetentionProperties();
        props.setOrphanImagesDays(2);
        props.setMessagesDays(90);

        MaintenanceAttachmentCleanupJob job = new MaintenanceAttachmentCleanupJob(
                attachmentRepository, conversationRepository, messageRepository, cloudinaryService, props, clock);

        MaintenanceTicketAttachment orphan = MaintenanceTicketAttachment.builder()
                .id(UUID.randomUUID())
                .storagePublicId("hesta/maintenance-attachments/orphan-1")
                .build();
        when(attachmentRepository.findByTicketIsNullAndCreatedAtBefore(
                OffsetDateTime.parse("2026-11-21T08:00:00Z"))).thenReturn(List.of(orphan));

        job.cleanupOrphanAttachments();
        verify(cloudinaryService).deleteImage("hesta/maintenance-attachments/orphan-1");
        verify(attachmentRepository).deleteAll(List.of(orphan));

        UUID oldConvId = UUID.randomUUID();
        when(conversationRepository.findFinishedIdsUpdatedBefore(
                eq(MaintenanceChatStatus.ACTIVE),
                eq(OffsetDateTime.parse("2026-08-25T08:00:00Z")))).thenReturn(List.of(oldConvId));
        when(messageRepository.deleteByConversationIdIn(List.of(oldConvId))).thenReturn(12);

        job.cleanupExpiredConversationMessages();
        verify(messageRepository).deleteByConversationIdIn(List.of(oldConvId));
        verify(conversationRepository, never()).delete(any());
    }

    @Test
    void deleteOwnedUnattachedDestroysCloudinaryAndBlocksTicketAttachedOrForeign() {
        MaintenanceAttachmentServiceImpl service = new MaintenanceAttachmentServiceImpl(
                homeAuthorizationService, userRepository, attachmentRepository, cloudinaryService, rateLimiter, new ImageSanitizer());

        UUID homeId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID attId = UUID.randomUUID();

        Home home = new Home(); home.setId(homeId);
        User owner = User.builder().id(ownerId).build();
        MaintenanceTicketAttachment unattached = MaintenanceTicketAttachment.builder()
                .id(attId).home(home).uploadedBy(owner).storagePublicId("hesta/maintenance-attachments/u1").build();

        when(attachmentRepository.findById(attId)).thenReturn(Optional.of(unattached));

        // Foreign user rejected
        assertThatThrownBy(() -> service.deleteOwnedUnattached(otherUserId, homeId, attId))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_ATTACHMENT_NOT_FOUND);
        verifyNoInteractions(cloudinaryService);

        // Attached to ticket rejected
        unattached.setTicket(new MaintenanceTicket());
        assertThatThrownBy(() -> service.deleteOwnedUnattached(ownerId, homeId, attId))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_INVALID_STATUS);
        verifyNoInteractions(cloudinaryService);

        // Owner deleting unattached -> Cloudinary destroy + DB delete
        unattached.setTicket(null);
        service.deleteOwnedUnattached(ownerId, homeId, attId);
        verify(cloudinaryService).deleteImage("hesta/maintenance-attachments/u1");
        verify(attachmentRepository).delete(unattached);
    }
}
