package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.dto.request.MaintenanceTimeSlotRequest;
import com.hesta.backend.dto.response.MaintenanceTicketResponse;
import com.hesta.backend.enums.MaintenanceChatRole;
import com.hesta.backend.enums.MaintenanceChatState;
import com.hesta.backend.enums.MaintenanceChatStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MaintenanceAssistantDtos {

    private MaintenanceAssistantDtos() {
    }

    public record StartConversationRequest(UUID deviceId) {
    }

    public enum MessageInputType {
        QUICK_REPLY,
        TEXT,
        IMAGE
    }

    public record SendMessageRequest(
            @NotNull(message = "MAINTENANCE_INVALID_REQUEST") MessageInputType type,
            @NotBlank(message = "MAINTENANCE_INVALID_REQUEST")
            @Size(max = 1000, message = "MAINTENANCE_INVALID_REQUEST") String value,
            @Size(max = 1000, message = "MAINTENANCE_INVALID_REQUEST") String note
    ) {
        public SendMessageRequest(MessageInputType type, String value) {
            this(type, value, null);
        }
    }

    public record ConfirmTicketRequest(
            @NotEmpty(message = "MAINTENANCE_INVALID_SLOT")
            @Size(max = 5, message = "MAINTENANCE_INVALID_SLOT")
            List<@Valid @NotNull(message = "MAINTENANCE_INVALID_SLOT") MaintenanceTimeSlotRequest> slots,

            @Size(max = 20, message = "MAINTENANCE_INVALID_REQUEST")
            String contactPhone,

            @Size(max = 5, message = "MAINTENANCE_ATTACHMENT_LIMIT_EXCEEDED")
            List<@NotNull(message = "MAINTENANCE_INVALID_REQUEST") UUID> attachmentIds,

            @Size(max = 1000, message = "MAINTENANCE_INVALID_REQUEST")
            String extraNote,

            @Size(max = 1500, message = "MAINTENANCE_INVALID_REQUEST")
            String editedSummary
    ) {
        public ConfirmTicketRequest(
                List<MaintenanceTimeSlotRequest> slots,
                String contactPhone,
                List<UUID> attachmentIds,
                String extraNote) {
            this(slots, contactPhone, attachmentIds, extraNote, null);
        }
    }

    public record MessageItem(
            UUID id,
            MaintenanceChatRole role,
            List<Map<String, Object>> blocks,
            OffsetDateTime createdAt
    ) {
    }

    public record ConversationResponse(
            UUID id,
            UUID homeId,
            UUID deviceId,
            MaintenanceChatState state,
            MaintenanceChatStatus status,
            UUID ticketId,
            String manualFallbackNote,
            boolean aiTextEnabled,
            boolean aiImageEnabled,
            Integer userRating,
            String userFeedbackNote,
            List<MessageItem> messages,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record MessageReplyResponse(
            UUID conversationId,
            MaintenanceChatState state,
            MaintenanceChatStatus status,
            UUID ticketId,
            String manualFallbackNote,
            boolean aiTextEnabled,
            boolean aiImageEnabled,
            List<Map<String, Object>> blocks
    ) {
    }

    public record ConversationFeedbackRequest(
            @NotNull(message = "MAINTENANCE_INVALID_REQUEST") Integer rating,
            @Size(max = 500, message = "MAINTENANCE_INVALID_REQUEST") String note
    ) {
    }

    public record ConversationFeedbackResponse(
            UUID conversationId,
            Integer userRating,
            String userFeedbackNote
    ) {
    }

    public record ConfirmTicketResponse(
            UUID conversationId,
            MaintenanceChatState state,
            MaintenanceChatStatus status,
            MaintenanceTicketResponse ticket,
            List<Map<String, Object>> blocks
    ) {
    }
}
