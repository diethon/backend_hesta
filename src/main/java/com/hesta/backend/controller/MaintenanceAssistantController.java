package com.hesta.backend.controller;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.maintenance.assistant.MaintenanceAssistantDtos;
import com.hesta.backend.maintenance.assistant.MaintenanceAssistantService;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.policy.AccessPolicyType;
import com.hesta.backend.security.policy.HomeAccessPolicy;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/maintenance-assistant")
@RequiredArgsConstructor
@HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Maintenance diagnostic assistant")
public class MaintenanceAssistantController {

    private final MaintenanceAssistantService assistantService;

    @PostMapping("/conversations")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Start a maintenance diagnostic conversation")
    public ResponseEntity<ApiResponse<MaintenanceAssistantDtos.ConversationResponse>> startConversation(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @RequestBody(required = false) MaintenanceAssistantDtos.StartConversationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ok(null, assistantService.startConversation(userId(user), homeId, request)));
    }

    @GetMapping("/conversations/active")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Get latest active diagnostic conversation for user")
    public ResponseEntity<ApiResponse<MaintenanceAssistantDtos.ConversationResponse>> getLatestActive(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId) {
        return ResponseEntity.ok(ok(null, assistantService.getLatestActiveConversation(userId(user), homeId)));
    }

    @GetMapping("/conversations/{conversationId}")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Get diagnostic conversation history")
    public ResponseEntity<ApiResponse<MaintenanceAssistantDtos.ConversationResponse>> getConversation(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID conversationId) {
        return ResponseEntity.ok(ok(null, assistantService.getConversation(userId(user), homeId, conversationId)));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Send a quick reply or text message in diagnostic conversation")
    public ResponseEntity<ApiResponse<MaintenanceAssistantDtos.MessageReplyResponse>> sendMessage(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody MaintenanceAssistantDtos.SendMessageRequest request) {
        return ResponseEntity.ok(ok(null, assistantService.sendMessage(userId(user), homeId, conversationId, request)));
    }

    @PostMapping("/conversations/{conversationId}/confirm-ticket")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Confirm and create maintenance ticket from diagnostic conversation")
    public ResponseEntity<ApiResponse<MaintenanceAssistantDtos.ConfirmTicketResponse>> confirmTicket(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody MaintenanceAssistantDtos.ConfirmTicketRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ok("Đã tạo yêu cầu bảo trì từ trợ lý",
                        assistantService.confirmTicket(userId(user), homeId, conversationId, request)));
    }

    @PostMapping("/conversations/{conversationId}/abandon")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Close an active diagnostic conversation")
    public ResponseEntity<ApiResponse<Void>> abandon(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID conversationId) {
        assistantService.abandonConversation(userId(user), homeId, conversationId);
        return ResponseEntity.ok(ok("Đã đóng phiên hỗ trợ", null));
    }

    @PostMapping("/conversations/{conversationId}/feedback")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Rate usefulness of a finished diagnostic conversation")
    public ResponseEntity<ApiResponse<MaintenanceAssistantDtos.ConversationFeedbackResponse>> submitFeedback(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody MaintenanceAssistantDtos.ConversationFeedbackRequest request) {
        return ResponseEntity.ok(ok("Cảm ơn phản hồi của bạn",
                assistantService.submitFeedback(userId(user), homeId, conversationId, request)));
    }

    @DeleteMapping("/conversations/{conversationId}")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Delete user's own diagnostic conversation and unattached images")
    public ResponseEntity<ApiResponse<Void>> deleteConversation(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID conversationId) {
        assistantService.deleteConversation(userId(user), homeId, conversationId);
        return ResponseEntity.ok(ok("Đã xoá lịch sử hội thoại", null));
    }

    private static UUID userId(CustomUserDetails user) {
        return user != null ? user.getId() : null;
    }

    private static <T> ApiResponse<T> ok(String message, T result) {
        return ApiResponse.<T>builder().code(1000).message(message).result(result).build();
    }
}
