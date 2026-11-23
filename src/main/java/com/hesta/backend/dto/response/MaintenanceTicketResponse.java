package com.hesta.backend.dto.response;

import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Chi tiết ticket bảo trì. Map bên trong transaction (Open Session in View đang tắt).
 */
public record MaintenanceTicketResponse(
        UUID id,
        String code,
        UUID homeId,
        String homeName,
        String homeAddress,
        String title,
        String description,
        MaintenancePriority priority,
        MaintenanceTicketStatus status,
        MaintenanceTicketSource source,
        String diagnosisSummary,
        String imageObservations,
        UUID chatConversationId,
        MaintenanceDiagnosisQuality diagnosisQuality,
        String diagnosisQualityNote,
        Person createdBy,
        String contactPhone,
        OffsetDateTime scheduledStart,
        OffsetDateTime scheduledEnd,
        boolean scheduleConfirmed,
        String adminNote,
        String cancelReason,
        List<DeviceLine> devices,
        List<Slot> slots,
        Quote quote,
        OffsetDateTime completedAt,
        Integer rating,
        String feedback,
        List<Event> events,
        List<Attachment> attachments,
        boolean canManage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /** Backwards-compatible constructor for existing callers/tests without diagnosisQuality fields. */
    public MaintenanceTicketResponse(
            UUID id, String code, UUID homeId, String homeName, String homeAddress,
            String title, String description, MaintenancePriority priority, MaintenanceTicketStatus status,
            MaintenanceTicketSource source, String diagnosisSummary, String imageObservations, UUID chatConversationId,
            Person createdBy, String contactPhone, OffsetDateTime scheduledStart, OffsetDateTime scheduledEnd,
            boolean scheduleConfirmed, String adminNote, String cancelReason, List<DeviceLine> devices,
            List<Slot> slots, Quote quote, OffsetDateTime completedAt, Integer rating, String feedback,
            List<Event> events, List<Attachment> attachments, boolean canManage,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, code, homeId, homeName, homeAddress, title, description, priority, status, source,
                diagnosisSummary, imageObservations, chatConversationId, null, null,
                createdBy, contactPhone, scheduledStart, scheduledEnd, scheduleConfirmed, adminNote, cancelReason,
                devices, slots, quote, completedAt, rating, feedback, events, attachments, canManage, createdAt, updatedAt);
    }

    public record Person(UUID id, String fullName, String email, String phoneNumber) {
        static Person of(User user) {
            return user == null ? null
                    : new Person(user.getId(), user.getFullName(), user.getEmail(), user.getPhoneNumber());
        }
    }

    public record DeviceLine(
            UUID id,
            UUID deviceId,
            String deviceName,
            String deviceType,
            String model,
            String serialNumber,
            String roomName,
            String issueNote,
            UUID sourceAnomalyId,
            MaintenanceDeviceOutcome outcome,
            String outcomeNote
    ) {
    }

    public record Slot(UUID id, OffsetDateTime startAt, OffsetDateTime endAt) {
    }

    public record QuoteItem(
            UUID id,
            UUID ticketDeviceId,
            MaintenanceItemType itemType,
            String description,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal amount
    ) {
    }

    public record Quote(
            String findings,
            Integer warrantyMonths,
            OffsetDateTime warrantyUntil,
            BigDecimal total,
            String currency,
            List<QuoteItem> items,
            OffsetDateTime submittedAt,
            MaintenanceQuoteDecision decision,
            OffsetDateTime decidedAt
    ) {
    }

    public record Event(
            UUID id,
            String actorName,
            MaintenanceActorRole actorRole,
            MaintenanceTicketStatus fromStatus,
            MaintenanceTicketStatus toStatus,
            String note,
            OffsetDateTime createdAt
    ) {
    }

    public record Attachment(
            UUID id,
            String url,
            String contentType,
            long sizeBytes,
            OffsetDateTime createdAt
    ) {
    }

    public static MaintenanceTicketResponse from(MaintenanceTicket t, boolean canManage) {
        Home home = t.getHome();
        List<DeviceLine> devices = t.getDevices().stream()
                .map(d -> new DeviceLine(
                        d.getId(),
                        d.getDevice() != null ? d.getDevice().getId() : null,
                        d.getDeviceName(),
                        d.getDeviceType(),
                        d.getModel(),
                        d.getSerialNumber(),
                        d.getRoomName(),
                        d.getIssueNote(),
                        d.getSourceAnomaly() != null ? d.getSourceAnomaly().getId() : null,
                        d.getOutcome(),
                        d.getOutcomeNote()))
                .toList();
        List<Slot> slots = t.getSlots().stream()
                .map(s -> new Slot(s.getId(), s.getStartAt(), s.getEndAt()))
                .toList();
        List<Event> events = t.getEvents().stream()
                .map(e -> new Event(
                        e.getId(),
                        e.getActor() != null ? e.getActor().getFullName() : null,
                        e.getActorRole(),
                        e.getFromStatus(),
                        e.getToStatus(),
                        e.getNote(),
                        e.getCreatedAt()))
                .toList();
        List<Attachment> attachments = t.getAttachments().stream()
                .map(a -> new Attachment(
                        a.getId(),
                        home != null && a.getId() != null
                                ? "/api/v1/homes/" + home.getId() + "/maintenance-attachments/" + a.getId() + "/content"
                                : a.getUrl(),
                        a.getContentType(),
                        a.getSizeBytes(),
                        a.getCreatedAt()))
                .toList();
        return new MaintenanceTicketResponse(
                t.getId(),
                t.getCode(),
                home != null ? home.getId() : null,
                home != null ? home.getName() : null,
                home != null ? home.getAddress() : null,
                t.getTitle(),
                t.getDescription(),
                t.getPriority(),
                t.getStatus(),
                t.getSource() != null ? t.getSource() : MaintenanceTicketSource.MANUAL,
                t.getDiagnosisSummary(),
                t.getImageObservations(),
                t.getChatConversationId(),
                t.getDiagnosisQuality(),
                t.getDiagnosisQualityNote(),
                Person.of(t.getCreatedBy()),
                t.getContactPhone(),
                t.getScheduledStart(),
                t.getScheduledEnd(),
                t.isScheduleConfirmed(),
                t.getAdminNote(),
                t.getCancelReason(),
                devices,
                slots,
                quoteOf(t),
                t.getCompletedAt(),
                t.getRating() != null ? t.getRating().intValue() : null,
                t.getFeedback(),
                events,
                attachments,
                canManage,
                t.getCreatedAt(),
                t.getUpdatedAt());
    }

    private static Quote quoteOf(MaintenanceTicket t) {
        if (t.getQuoteSubmittedAt() == null) {
            return null;
        }
        List<QuoteItem> items = t.getQuoteItems().stream()
                .map(i -> new QuoteItem(
                        i.getId(),
                        i.getTicketDevice() != null ? i.getTicketDevice().getId() : null,
                        i.getItemType(),
                        i.getDescription(),
                        i.getQuantity(),
                        i.getUnitPrice(),
                        i.getAmount()))
                .toList();
        OffsetDateTime warrantyUntil = t.getCompletedAt() != null && t.getWarrantyMonths() != null
                && t.getWarrantyMonths() > 0
                ? t.getCompletedAt().plusMonths(t.getWarrantyMonths())
                : null;
        return new Quote(
                t.getFindings(),
                t.getWarrantyMonths(),
                warrantyUntil,
                t.getQuoteTotal(),
                "VND",
                items,
                t.getQuoteSubmittedAt(),
                t.getQuoteDecision(),
                t.getQuoteDecidedAt());
    }
}
