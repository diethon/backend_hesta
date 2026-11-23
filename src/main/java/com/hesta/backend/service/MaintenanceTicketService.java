package com.hesta.backend.service;

import com.hesta.backend.dto.request.*;
import com.hesta.backend.dto.response.MaintenanceTicketResponse;
import com.hesta.backend.dto.response.MaintenanceTicketSummaryResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.enums.MaintenanceTicketStatus;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ticket bảo trì thiết bị: người dùng báo hỏng + đề xuất giờ rảnh, admin xếp lịch, kiểm tra,
 * báo giá; người dùng duyệt giá; admin hoàn tất kèm kết luận từng thiết bị.
 */
public interface MaintenanceTicketService {

    // ---- Home user (OWNER / MEMBER) ----

    MaintenanceTicketResponse create(UUID userId, UUID homeId, CreateMaintenanceTicketRequest request);

    PageResponse<MaintenanceTicketSummaryResponse> listForHome(
            UUID userId, UUID homeId, MaintenanceTicketStatus status, UUID deviceId, Pageable pageable);

    MaintenanceTicketResponse getForHome(UUID userId, UUID homeId, UUID ticketId);

    MaintenanceTicketResponse cancel(UUID userId, UUID homeId, UUID ticketId, MaintenanceNoteRequest request);

    MaintenanceTicketResponse confirmSchedule(UUID userId, UUID homeId, UUID ticketId);

    MaintenanceTicketResponse requestReschedule(UUID userId, UUID homeId, UUID ticketId, RescheduleMaintenanceRequest request);

    MaintenanceTicketResponse approveQuote(UUID userId, UUID homeId, UUID ticketId);

    MaintenanceTicketResponse rejectQuote(UUID userId, UUID homeId, UUID ticketId, MaintenanceNoteRequest request);

    MaintenanceTicketResponse submitFeedback(UUID userId, UUID homeId, UUID ticketId, MaintenanceFeedbackRequest request);

    // ---- Platform admin ----

    PageResponse<MaintenanceTicketSummaryResponse> listForAdmin(
            MaintenanceTicketStatus status, OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    MaintenanceTicketResponse getForAdmin(UUID ticketId);

    MaintenanceTicketResponse schedule(UUID adminId, UUID ticketId, ScheduleMaintenanceRequest request);

    MaintenanceTicketResponse startInspection(UUID adminId, UUID ticketId, MaintenanceNoteRequest request);

    MaintenanceTicketResponse submitQuote(UUID adminId, UUID ticketId, SubmitMaintenanceQuoteRequest request);

    MaintenanceTicketResponse complete(UUID adminId, UUID ticketId, CompleteMaintenanceRequest request);

    MaintenanceTicketResponse reject(UUID adminId, UUID ticketId, MaintenanceNoteRequest request);
}
