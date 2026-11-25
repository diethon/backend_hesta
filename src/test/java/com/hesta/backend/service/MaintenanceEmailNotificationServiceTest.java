package com.hesta.backend.service;

import com.hesta.backend.config.MaintenanceEmailProperties;
import com.hesta.backend.enums.MaintenancePriority;
import com.hesta.backend.enums.MaintenanceTicketSource;
import com.hesta.backend.enums.MaintenanceTicketStatus;
import com.hesta.backend.event.MaintenanceEmailListener;
import com.hesta.backend.event.MaintenanceTicketEmailEvent;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaintenanceEmailNotificationServiceTest {

    @Mock private JavaMailSender mailSender;

    private MaintenanceEmailProperties properties;
    private MaintenanceEmailNotificationService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-23T08:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        properties = new MaintenanceEmailProperties();
        properties.setEnabled(true);
        properties.setAdminRecipients(List.of("admin1@hesta.vn", "admin2@hesta.vn"));
        properties.setMaxRetries(1);
        properties.setRetryBackoffMs(0);
        properties.setMaxPerTicketPerHour(2);
        service = new MaintenanceEmailNotificationService(
                mailSender, properties, clock, "noreply@hesta.vn", "https://app.hesta.vn");
    }

    @Test
    void doesNotSendWhenDisabledByDefault() {
        properties.setEnabled(false);
        MaintenanceTicketEmailEvent event = sampleEvent(MaintenanceTicketEmailEvent.EventKind.TICKET_CREATED_FOR_ADMIN, Set.of());
        assertThat(service.sendForEvent(event)).isZero();
        verifyNoInteractions(mailSender);
    }

    @Test
    void sendsToAdminRecipientsWithUrgentAndChatbotPrefixesAndEscapesHtml() {
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        MaintenanceTicketEmailEvent event = new MaintenanceTicketEmailEvent(
                UUID.randomUUID(), "MT-261123-URG01", UUID.randomUUID(),
                "<script>alert('xss')</script> Đèn cháy",
                MaintenancePriority.URGENT, MaintenanceTicketSource.CHATBOT,
                MaintenanceTicketStatus.SUBMITTED,
                MaintenanceTicketEmailEvent.EventKind.TICKET_CREATED_FOR_ADMIN,
                Set.of());

        String subject = service.buildSubject(event);
        assertThat(subject).contains("[KHẨN CẤP]").contains("[Trợ lý AI]").contains("MT-261123-URG01");

        String html = service.buildHtml(event);
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;");
        assertThat(html).contains("https://app.hesta.vn/admin/maintenance?ticket=" + event.ticketId());

        int sent = service.sendForEvent(event);
        assertThat(sent).isEqualTo(2);
        verify(mailSender, times(2)).send(any(MimeMessage.class));
    }

    @Test
    void smtpFailureDoesNotThrowOutOfListenerAndHourlyRateLimitBlocksSpam() {
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(MimeMessage.class));

        MaintenanceEmailListener listener = new MaintenanceEmailListener(service);
        MaintenanceTicketEmailEvent event = sampleEvent(
                MaintenanceTicketEmailEvent.EventKind.TICKET_SCHEDULED, Set.of("owner@hesta.vn"));

        assertThatCode(() -> listener.onTicketEmailEvent(event)).doesNotThrowAnyException();

        // Reset stub to succeed and test hourly rate limit (maxPerTicketPerHour = 2; 1 already consumed above)
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        assertThat(service.sendForEvent(event)).isEqualTo(1); // 2nd allowed
        assertThat(service.sendForEvent(event)).isZero();     // 3rd blocked by hourly limit
    }

    private MaintenanceTicketEmailEvent sampleEvent(
            MaintenanceTicketEmailEvent.EventKind kind, Set<String> recipients) {
        return new MaintenanceTicketEmailEvent(
                UUID.randomUUID(), "MT-261123-TEST1", UUID.randomUUID(),
                "Kiểm tra Đèn trần", MaintenancePriority.NORMAL, MaintenanceTicketSource.MANUAL,
                MaintenanceTicketStatus.SCHEDULED, kind, recipients);
    }
}
