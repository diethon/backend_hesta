package com.hesta.backend.job;

import com.hesta.backend.entity.MaintenanceTicketAttachment;
import com.hesta.backend.enums.MaintenanceChatStatus;
import com.hesta.backend.maintenance.assistant.MaintenanceRetentionProperties;
import com.hesta.backend.repository.MaintenanceChatConversationRepository;
import com.hesta.backend.repository.MaintenanceChatMessageRepository;
import com.hesta.backend.repository.MaintenanceTicketAttachmentRepository;
import com.hesta.backend.service.CloudinaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Job lưu giữ dữ liệu & quyền riêng tư cho trợ lý bảo trì:
 * 1. Dọn ảnh mồ côi (chưa gắn ticket) quá hạn trên cả Cloudinary và DB. Ảnh đã gắn ticket KHÔNG bao giờ bị xóa ở đây.
 * 2. Xóa tin nhắn thô của hội thoại đã kết thúc sau N ngày (mặc định 90 ngày), giữ nguyên số liệu tổng hợp/sự kiện & diagnosisSummary của ticket.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MaintenanceAttachmentCleanupJob {

    private final MaintenanceTicketAttachmentRepository attachmentRepository;
    private final MaintenanceChatConversationRepository conversationRepository;
    private final MaintenanceChatMessageRepository messageRepository;
    private final CloudinaryService cloudinaryService;
    private final MaintenanceRetentionProperties retentionProperties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${hesta.maintenance.attachment.cleanup-interval:1h}")
    @Transactional
    public void cleanupOrphanAttachments() {
        int days = Math.max(1, retentionProperties.getOrphanImagesDays());
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(days);
        List<MaintenanceTicketAttachment> orphans = attachmentRepository.findByTicketIsNullAndCreatedAtBefore(cutoff);
        if (orphans.isEmpty()) {
            return;
        }
        for (MaintenanceTicketAttachment orphan : orphans) {
            cloudinaryService.deleteImage(orphan.getStoragePublicId());
        }
        attachmentRepository.deleteAll(orphans);
        log.info("Cleaned up {} orphan maintenance attachments older than {}", orphans.size(), cutoff);
    }

    @Scheduled(cron = "${hesta.assistant.retention.messages-cron:0 30 3 * * *}")
    @Transactional
    public void cleanupExpiredConversationMessages() {
        int days = Math.max(1, retentionProperties.getMessagesDays());
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(days);
        List<UUID> expiredConvIds = conversationRepository.findFinishedIdsUpdatedBefore(
                MaintenanceChatStatus.ACTIVE, cutoff);
        if (expiredConvIds.isEmpty()) {
            return;
        }
        int deletedMessages = messageRepository.deleteByConversationIdIn(expiredConvIds);
        if (deletedMessages > 0) {
            log.info("Retention job purged {} raw messages from {} finished conversations older than {}",
                    deletedMessages, expiredConvIds.size(), cutoff);
        }
    }
}
