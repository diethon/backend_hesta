package com.hesta.backend.controller;

import com.hesta.backend.dto.request.CompleteMaintenanceRequest;
import com.hesta.backend.dto.request.MaintenanceNoteRequest;
import com.hesta.backend.dto.request.ScheduleMaintenanceRequest;
import com.hesta.backend.dto.request.SubmitMaintenanceQuoteRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.MaintenanceTicketResponse;
import com.hesta.backend.dto.response.MaintenanceTicketSummaryResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.enums.MaintenanceTicketStatus;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.MaintenanceTicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/maintenance-tickets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMaintenanceTicketController {

    private final MaintenanceTicketService maintenanceTicketService;

    /** from/to (ISO-8601) filter on the scheduled visit start, used by the calendar view. */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<MaintenanceTicketSummaryResponse>>> list(
            @RequestParam(required = false) MaintenanceTicketStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ok(null, maintenanceTicketService.listForAdmin(status, from, to, pageable)));
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> get(@PathVariable UUID ticketId) {
        return ResponseEntity.ok(ok(null, maintenanceTicketService.getForAdmin(ticketId)));
    }

    @PostMapping("/{ticketId}/schedule")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> schedule(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable UUID ticketId,
            @Valid @RequestBody ScheduleMaintenanceRequest request) {
        return ResponseEntity.ok(ok("Đã gửi lịch hẹn cho người dùng",
                maintenanceTicketService.schedule(admin.getId(), ticketId, request)));
    }

    @PostMapping("/{ticketId}/start-inspection")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> startInspection(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable UUID ticketId,
            @Valid @RequestBody(required = false) MaintenanceNoteRequest request) {
        return ResponseEntity.ok(ok("Đã bắt đầu kiểm tra",
                maintenanceTicketService.startInspection(admin.getId(), ticketId, request)));
    }

    @PutMapping("/{ticketId}/quote")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> submitQuote(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable UUID ticketId,
            @Valid @RequestBody SubmitMaintenanceQuoteRequest request) {
        return ResponseEntity.ok(ok("Đã gửi báo giá",
                maintenanceTicketService.submitQuote(admin.getId(), ticketId, request)));
    }

    @PostMapping("/{ticketId}/complete")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> complete(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable UUID ticketId,
            @Valid @RequestBody CompleteMaintenanceRequest request) {
        return ResponseEntity.ok(ok("Đã hoàn tất ticket",
                maintenanceTicketService.complete(admin.getId(), ticketId, request)));
    }

    @PostMapping("/{ticketId}/reject")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> reject(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable UUID ticketId,
            @Valid @RequestBody(required = false) MaintenanceNoteRequest request) {
        return ResponseEntity.ok(ok("Đã từ chối ticket",
                maintenanceTicketService.reject(admin.getId(), ticketId, request)));
    }

    public record DiagnosisQualityRequest(
            @jakarta.validation.constraints.NotNull com.hesta.backend.enums.MaintenanceDiagnosisQuality quality,
            @jakarta.validation.constraints.Size(max = 500) String note
    ) {
    }

    @PutMapping("/{ticketId}/diagnosis-quality")
    public ResponseEntity<ApiResponse<MaintenanceTicketResponse>> rateDiagnosisQuality(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable UUID ticketId,
            @Valid @RequestBody DiagnosisQualityRequest request) {
        return ResponseEntity.ok(ok("Đã lưu đánh giá chất lượng chẩn đoán",
                maintenanceTicketService.rateDiagnosisQuality(admin.getId(), ticketId, request.quality(), request.note())));
    }

    private static <T> ApiResponse<T> ok(String message, T result) {
        return ApiResponse.<T>builder().code(1000).message(message).result(result).build();
    }
}
