package com.hesta.backend.service;

import com.hesta.backend.config.MaintenanceEmailProperties;
import com.hesta.backend.enums.MaintenancePriority;
import com.hesta.backend.enums.MaintenanceTicketSource;
import com.hesta.backend.enums.MaintenanceTicketStatus;
import com.hesta.backend.event.MaintenanceTicketEmailEvent;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dịch vụ gửi email thông báo ticket bảo trì dùng SMTP hiện có.
 * - Chỉ gửi mã ticket, tiêu đề (đã escape HTML), trạng thái và link vào ứng dụng.
 * - KHÔNG đính ảnh, KHÔNG đưa mô tả chi tiết hay observations AI.
 * - Có rate limit theo ticket/giờ và retry giới hạn; lỗi SMTP không bao giờ làm hỏng transaction ticket.
 */
@Service
@Slf4j
public class MaintenanceEmailNotificationService {

    private final JavaMailSender mailSender;
    private final MaintenanceEmailProperties properties;
    private final Clock clock;
    private final String fromEmail;
    private final String frontendUrl;

    private final ConcurrentHashMap<UUID, Deque<Instant>> sentTimestampsByTicket = new ConcurrentHashMap<>();

    public MaintenanceEmailNotificationService(
            JavaMailSender mailSender,
            MaintenanceEmailProperties properties,
            Clock clock,
            @Value("${spring.mail.username:hestasmarthome@gmail.com}") String fromEmail,
            @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
        this.fromEmail = fromEmail;
        this.frontendUrl = frontendUrl;
    }

    public int sendForEvent(MaintenanceTicketEmailEvent event) {
        if (!properties.isEnabled() || event == null) {
            return 0;
        }
        Set<String> recipients = resolveRecipients(event);
        if (recipients.isEmpty()) {
            return 0;
        }
        if (!tryConsumeTicketHourlyQuota(event.ticketId())) {
            log.warn("Skipped maintenance email for ticket {} due to hourly rate limit", event.ticketCode());
            return 0;
        }

        String subject = buildSubject(event);
        String html = buildHtml(event);
        int sentCount = 0;
        for (String to : recipients) {
            if (sendWithRetry(to, subject, html, event.ticketCode())) {
                sentCount++;
            }
        }
        return sentCount;
    }

    private Set<String> resolveRecipients(MaintenanceTicketEmailEvent event) {
        Set<String> result = new LinkedHashSet<>();
        if (event.kind() == MaintenanceTicketEmailEvent.EventKind.TICKET_CREATED_FOR_ADMIN) {
            if (properties.getAdminRecipients() != null) {
                for (String r : properties.getAdminRecipients()) {
                    if (r != null && !r.isBlank()) {
                        result.add(r.trim());
                    }
                }
            }
        } else if (event.recipientEmails() != null) {
            for (String r : event.recipientEmails()) {
                if (r != null && !r.isBlank()) {
                    result.add(r.trim());
                }
            }
        }
        return result;
    }

    private boolean tryConsumeTicketHourlyQuota(UUID ticketId) {
        int maxPerHour = properties.getMaxPerTicketPerHour();
        if (maxPerHour <= 0 || ticketId == null) {
            return true;
        }
        Instant now = clock.instant();
        Instant cutoff = now.minusSeconds(3600);
        Deque<Instant> bucket = sentTimestampsByTicket.computeIfAbsent(ticketId, k -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst().isBefore(cutoff)) {
                bucket.pollFirst();
            }
            if (bucket.size() >= maxPerHour) {
                return false;
            }
            bucket.addLast(now);
            return true;
        }
    }

    String buildSubject(MaintenanceTicketEmailEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.kind() == MaintenanceTicketEmailEvent.EventKind.TICKET_CREATED_FOR_ADMIN) {
            if (event.priority() == MaintenancePriority.URGENT) {
                sb.append("[KHẨN CẤP] ");
            }
            if (event.source() == MaintenanceTicketSource.CHATBOT) {
                sb.append("[Trợ lý AI] ");
            }
            sb.append("Ticket bảo trì mới ").append(safePlain(event.ticketCode()));
            return sb.toString();
        }
        String actionLabel = switch (event.kind()) {
            case TICKET_SCHEDULED -> "Đã xếp lịch hẹn";
            case TICKET_QUOTED -> "Có báo giá sửa chữa";
            case TICKET_COMPLETED -> "Đã hoàn tất bảo trì";
            case TICKET_REJECTED -> "Yêu cầu bảo trì bị từ chối";
            default -> "Cập nhật yêu cầu bảo trì";
        };
        return "[HESTA] " + actionLabel + " — " + safePlain(event.ticketCode());
    }

    String buildHtml(MaintenanceTicketEmailEvent event) {
        boolean forAdmin = event.kind() == MaintenanceTicketEmailEvent.EventKind.TICKET_CREATED_FOR_ADMIN;
        String base = frontendUrl != null ? frontendUrl.replaceAll("/+$", "") : "http://localhost:5173";
        String link = forAdmin
                ? base + "/admin/maintenance?ticket=" + event.ticketId()
                : base + "/homes/" + event.homeId() + "/maintenance?ticket=" + event.ticketId();

        String safeCode = escapeHtml(event.ticketCode());
        String safeTitle = escapeHtml(event.title());
        String safeStatus = escapeHtml(statusLabel(event.status()));

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><body style=\"font-family:Segoe UI,Arial,sans-serif;background:#f8fafc;padding:20px;margin:0;\">");
        sb.append("<div style=\"max-width:560px;margin:0 auto;background:#ffffff;border-radius:12px;padding:28px;border:1px solid #e2e8f0;\">");
        sb.append("<h2 style=\"margin:0 0 12px;color:#0f172a;font-size:18px;\">HESTA Smart Home — Thông báo bảo trì</h2>");
        sb.append("<p style=\"margin:0 0 8px;color:#334155;font-size:14px;\"><strong>Mã ticket:</strong> ").append(safeCode).append("</p>");
        sb.append("<p style=\"margin:0 0 8px;color:#334155;font-size:14px;\"><strong>Tiêu đề:</strong> ").append(safeTitle).append("</p>");
        sb.append("<p style=\"margin:0 0 18px;color:#334155;font-size:14px;\"><strong>Trạng thái:</strong> ").append(safeStatus).append("</p>");
        sb.append("<p style=\"margin:0 0 20px;\"><a href=\"").append(escapeHtml(link))
                .append("\" style=\"display:inline-block;padding:10px 18px;background:#0891b2;color:#ffffff;text-decoration:none;border-radius:8px;font-weight:600;font-size:14px;\">Xem chi tiết trên ứng dụng HESTA</a></p>");
        sb.append("<p style=\"margin:16px 0 0;padding-top:12px;border-top:1px solid #e2e8f0;color:#64748b;font-size:12px;\">");
        sb.append("Vì lý do bảo mật, email không đính kèm ảnh hay nội dung chẩn đoán chi tiết. ");
        if (!forAdmin) {
            sb.append("Bạn có thể tắt email thông báo bảo trì trong mục Bảo trì hoặc Hồ sơ cá nhân trên ứng dụng HESTA.");
        }
        sb.append("</p></div></body></html>");
        return sb.toString();
    }

    private boolean sendWithRetry(String to, String subject, String html, String ticketCode) {
        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                MimeMessage msg = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
                helper.setFrom(fromEmail);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(html, true);
                mailSender.send(msg);
                log.info("Sent maintenance email for ticket {} (attempt {}/{})", ticketCode, attempt, attempts);
                return true;
            } catch (Exception ex) {
                log.warn("Maintenance email failed for ticket {} (attempt {}/{}): {}",
                        ticketCode, attempt, attempts, ex.getClass().getSimpleName());
                if (attempt < attempts && properties.getRetryBackoffMs() > 0) {
                    try {
                        Thread.sleep(properties.getRetryBackoffMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    static String escapeHtml(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String safePlain(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\r\\n]", " ").trim();
    }

    private static String statusLabel(MaintenanceTicketStatus status) {
        if (status == null) return "Không xác định";
        return switch (status) {
            case SUBMITTED -> "Đã gửi yêu cầu";
            case SCHEDULED -> "Đã xếp lịch hẹn";
            case RESCHEDULE_REQUESTED -> "Yêu cầu đổi lịch";
            case INSPECTING -> "Đang kiểm tra";
            case QUOTED -> "Đã gửi báo giá";
            case REPAIRING -> "Đang sửa chữa";
            case COMPLETED -> "Đã hoàn tất";
            case CLOSED -> "Đã đóng";
            case CANCELLED -> "Đã huỷ";
            case REJECTED -> "Bị từ chối";
        };
    }
}
