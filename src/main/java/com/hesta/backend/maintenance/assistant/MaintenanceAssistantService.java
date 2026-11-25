package com.hesta.backend.maintenance.assistant;

import java.util.UUID;

public interface MaintenanceAssistantService {

    MaintenanceAssistantDtos.ConversationResponse startConversation(
            UUID userId, UUID homeId, MaintenanceAssistantDtos.StartConversationRequest request);

    MaintenanceAssistantDtos.ConversationResponse getConversation(UUID userId, UUID homeId, UUID conversationId);

    MaintenanceAssistantDtos.ConversationResponse getLatestActiveConversation(UUID userId, UUID homeId);

    MaintenanceAssistantDtos.MessageReplyResponse sendMessage(
            UUID userId, UUID homeId, UUID conversationId, MaintenanceAssistantDtos.SendMessageRequest request);

    MaintenanceAssistantDtos.ConfirmTicketResponse confirmTicket(
            UUID userId, UUID homeId, UUID conversationId, MaintenanceAssistantDtos.ConfirmTicketRequest request);

    void abandonConversation(UUID userId, UUID homeId, UUID conversationId);

    MaintenanceAssistantDtos.ConversationFeedbackResponse submitFeedback(
            UUID userId, UUID homeId, UUID conversationId, MaintenanceAssistantDtos.ConversationFeedbackRequest request);

    void deleteConversation(UUID userId, UUID homeId, UUID conversationId);
}
