package com.hesta.backend.controller;

import com.hesta.backend.dto.request.*;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.MaintenanceTicketResponse;
import com.hesta.backend.dto.response.MaintenanceTicketSummaryResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.enums.MaintenanceTicketStatus;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.policy.AccessPolicyType;
import com.hesta.backend.security.policy.HomeAccessPolicy;
import com.hesta.backend.service.MaintenanceTicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/homes/{homeId}/maintenance-tickets")
@RequiredArgsConstructor
@HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Maintenance tickets of a home")
public class MaintenanceTicketController {

    private final MaintenanceTicketService maintenanceTicketService;

    @PostMapping
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Report broken devices; device access checked in service")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> create(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @Valid @RequestBody CreateMaintenanceTicketRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ok("Đã gửi yêu cầu bảo trì", maintenanceTicketService.create(userId(user), homeId, request)));
    }

    @GetMapping
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "List tickets visible to the member")
    public ResponseEntity<ApiResponse<PageResponse<MaintenanceTicketSummaryResponse>>> list(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @RequestParam(required = false) MaintenanceTicketStatus status,
            @RequestParam(required = false) UUID deviceId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ok(null,
                maintenanceTicketService.listForHome(userId(user), homeId, status, deviceId, pageable)));
    }

    @GetMapping("/{ticketId}")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Ticket detail")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> get(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ticketId) {
        return ResponseEntity.ok(ok(null, maintenanceTicketService.getForHome(userId(user), homeId, ticketId)));
    }

    @PostMapping("/{ticketId}/cancel")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Owner or creator cancels the ticket")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> cancel(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ticketId,
            @Valid @RequestBody(required = false) MaintenanceNoteRequest request) {
        return ResponseEntity.ok(ok("Đã huỷ yêu cầu bảo trì",
                maintenanceTicketService.cancel(userId(user), homeId, ticketId, request)));
    }

    @PostMapping("/{ticketId}/schedule/confirm")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Owner or creator confirms the proposed visit")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> confirmSchedule(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ticketId) {
        return ResponseEntity.ok(ok("Đã xác nhận lịch hẹn",
                maintenanceTicketService.confirmSchedule(userId(user), homeId, ticketId)));
    }

    @PostMapping("/{ticketId}/schedule/reschedule")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Owner or creator proposes new free slots")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> reschedule(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ticketId,
            @Valid @RequestBody RescheduleMaintenanceRequest request) {
        return ResponseEntity.ok(ok("Đã gửi khung giờ mới",
                maintenanceTicketService.requestReschedule(userId(user), homeId, ticketId, request)));
    }

    @PostMapping("/{ticketId}/quote/approve")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Owner or creator approves the repair quote")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> approveQuote(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ticketId) {
        return ResponseEntity.ok(ok("Đã duyệt báo giá",
                maintenanceTicketService.approveQuote(userId(user), homeId, ticketId)));
    }

    @PostMapping("/{ticketId}/quote/reject")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Owner or creator rejects the repair quote")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> rejectQuote(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ticketId,
            @Valid @RequestBody(required = false) MaintenanceNoteRequest request) {
        return ResponseEntity.ok(ok("Đã từ chối báo giá",
                maintenanceTicketService.rejectQuote(userId(user), homeId, ticketId, request)));
    }

    @PostMapping("/{ticketId}/feedback")
    @HomeAccessPolicy(value = AccessPolicyType.HOME_ACCESS, description = "Owner or creator rates a finished ticket")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> feedback(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID homeId,
            @PathVariable UUID ticketId,
            @Valid @RequestBody MaintenanceFeedbackRequest request) {
        return ResponseEntity.ok(ok("Cảm ơn bạn đã đánh giá",
                maintenanceTicketService.submitFeedback(userId(user), homeId, ticketId, request)));
    }

    private static UUID userId(CustomUserDetails user) {
        return user != null ? user.getId() : null;
    }

    private static <T> ApiResponse<T> ok(String message, T result) {
        return ApiResponse.<T>builder().code(1000).message(message).result(result).build();
    }
}
