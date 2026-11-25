package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.*;
import com.hesta.backend.dto.response.MaintenanceTicketResponse;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.event.MaintenanceNotificationEvent;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.AnomalyDetectionService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaintenanceTicketServiceImplTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    // Monday 2026-11-23 08:00 in Vietnam.
    private static final Instant NOW = ZonedDateTime.of(2026, 11, 23, 8, 0, 0, 0, VN).toInstant();

    @Mock private MaintenanceTicketRepository ticketRepository;
    @Mock private MaintenanceTicketAttachmentRepository attachmentRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private DeviceAnomalyRepository deviceAnomalyRepository;
    @Mock private UserRepository userRepository;
    @Mock private HomeMemberRepository homeMemberRepository;
    @Mock private HomeAuthorizationService homeAuthorizationService;
    @Mock private AnomalyDetectionService anomalyDetectionService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private MaintenanceTicketServiceImpl service;

    private final UUID homeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private Home home;
    private User user;
    private User owner;
    private Device device;

    @BeforeEach
    void setUp() {
        service = new MaintenanceTicketServiceImpl(ticketRepository, attachmentRepository, deviceRepository, deviceAnomalyRepository,
                userRepository, homeMemberRepository, homeAuthorizationService, anomalyDetectionService,
                eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));

        home = new Home();
        home.setId(homeId);
        home.setName("Nhà A");
        user = User.builder().id(userId).fullName("Member").email("m@x.vn").phoneNumber("0900").build();
        owner = User.builder().id(ownerId).fullName("Owner").email("o@x.vn").build();
        User admin = User.builder().id(adminId).fullName("Admin").email("a@x.vn").build();

        Room room = new Room();
        room.setId(UUID.randomUUID());
        room.setName("Phòng khách");
        room.setHome(home);
        device = new Device();
        device.setId(deviceId);
        device.setName("Đèn trần");
        device.setDeviceType("LIGHT");
        device.setRoom(room);

        when(homeAuthorizationService.requireAccess(any(), eq(homeId))).thenReturn(home);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(ticketRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(homeMemberRepository.findByHomeIdWithUser(homeId)).thenReturn(List.of(
                member(owner, HomeRole.OWNER), member(user, HomeRole.MEMBER)));
    }

    // ---- create ----

    @Test
    void createSnapshotsDeviceAndNotifiesOwner() {
        memberWithDeviceLevel(AccessLevel.VIEW);

        MaintenanceTicketResponse response = service.create(userId, homeId, createRequest(validSlot(1)));

        assertThat(response.status()).isEqualTo(MaintenanceTicketStatus.SUBMITTED);
        assertThat(response.code()).startsWith("MT-261123-");
        assertThat(response.devices()).singleElement().satisfies(d -> {
            assertThat(d.deviceName()).isEqualTo("Đèn trần");
            assertThat(d.roomName()).isEqualTo("Phòng khách");
        });
        assertThat(response.contactPhone()).isEqualTo("0900");
        assertThat(response.events()).hasSize(1);

        ArgumentCaptor<MaintenanceNotificationEvent> event = ArgumentCaptor.forClass(MaintenanceNotificationEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().recipientIds()).containsExactly(ownerId);
    }

    @Test
    void createRejectsDeviceMemberCannotSee() {
        memberWithDeviceLevel(AccessLevel.NONE);

        assertThatThrownBy(() -> service.create(userId, homeId, createRequest(validSlot(1))))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEVICE_NOT_FOUND);
    }

    @Test
    void createRejectsDeviceFromAnotherHome() {
        memberWithDeviceLevel(AccessLevel.CONTROL);
        Home other = new Home();
        other.setId(UUID.randomUUID());
        device.getRoom().setHome(other);

        assertThatThrownBy(() -> service.create(userId, homeId, createRequest(validSlot(1))))
                .extracting("errorCode").isEqualTo(ErrorCode.DEVICE_NOT_FOUND);
    }

    @Test
    void createRejectsDeviceWithActiveTicket() {
        memberWithDeviceLevel(AccessLevel.VIEW);
        when(ticketRepository.existsDeviceInStatuses(eq(deviceId), any())).thenReturn(true);

        assertThatThrownBy(() -> service.create(userId, homeId, createRequest(validSlot(1))))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_DEVICE_HAS_ACTIVE_TICKET);
    }

    // ---- slot rules ----

    @Test
    void slotsMustRespectLeadTimeWorkingHoursHorizonAndNoOverlap() {
        // Too soon: today 09:00 is only 1h after NOW.
        assertInvalidSlots(slot(0, 9, 0, 11, 0));
        // Outside working hours.
        assertInvalidSlots(slot(1, 6, 0, 8, 0));
        assertInvalidSlots(slot(1, 20, 0, 22, 0));
        // Too short / too long.
        assertInvalidSlots(slot(1, 9, 0, 9, 15));
        assertInvalidSlots(slot(1, 7, 0, 16, 0));
        // Beyond 14 days.
        assertInvalidSlots(slot(15, 9, 0, 11, 0));
        // Overlapping slots.
        assertInvalidSlots(slot(1, 9, 0, 11, 0), slot(1, 10, 0, 12, 0));

        assertThat(service.toValidatedSlots(List.of(slot(1, 18, 0, 20, 0), slot(0, 14, 0, 16, 0))))
                .hasSize(2)
                .first().satisfies(s -> assertThat(s.getStartAt()).isBefore(OffsetDateTime.ofInstant(NOW, VN).plusDays(1)));
    }

    // ---- schedule ----

    @Test
    void scheduleInsideUserSlotIsAutoConfirmed() {
        MaintenanceTicket ticket = ticketWithStatus(MaintenanceTicketStatus.SUBMITTED);
        MaintenanceTimeSlotRequest free = slot(1, 9, 0, 12, 0);
        ticket.replaceSlots(service.toValidatedSlots(List.of(free)));

        MaintenanceTicketResponse response = service.schedule(adminId, ticket.getId(),
                new ScheduleMaintenanceRequest(free.startAt().plusHours(1), free.startAt().plusHours(2), null));

        assertThat(response.status()).isEqualTo(MaintenanceTicketStatus.SCHEDULED);
        assertThat(response.scheduleConfirmed()).isTrue();
    }

    @Test
    void scheduleOutsideUserSlotNeedsConfirmationAndConflictsAreBlocked() {
        MaintenanceTicket ticket = ticketWithStatus(MaintenanceTicketStatus.SUBMITTED);
        ticket.replaceSlots(service.toValidatedSlots(List.of(slot(1, 9, 0, 12, 0))));
        MaintenanceTimeSlotRequest other = slot(2, 14, 0, 15, 0);

        MaintenanceTicketResponse response = service.schedule(adminId, ticket.getId(),
                new ScheduleMaintenanceRequest(other.startAt(), other.endAt(), null));
        assertThat(response.scheduleConfirmed()).isFalse();

        assertThatThrownBy(() -> service.startInspection(adminId, ticket.getId(), null))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_INVALID_STATUS);

        when(ticketRepository.existsScheduleOverlap(any(), any(), any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.schedule(adminId, ticket.getId(),
                new ScheduleMaintenanceRequest(other.startAt(), other.endAt(), null)))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_SCHEDULE_CONFLICT);
    }

    // ---- quote / approval ----

    @Test
    void quoteTotalIsComputedServerSideAndOnlyManagerCanApprove() {
        MaintenanceTicket ticket = ticketWithStatus(MaintenanceTicketStatus.INSPECTING);
        UUID lineId = ticket.getDevices().get(0).getId();

        MaintenanceTicketResponse quoted = service.submitQuote(adminId, ticket.getId(), new SubmitMaintenanceQuoteRequest(
                "Hỏng driver LED", 6, List.of(
                new SubmitMaintenanceQuoteRequest.Item(lineId, MaintenanceItemType.PART, "Driver LED", 2, new BigDecimal("150000")),
                new SubmitMaintenanceQuoteRequest.Item(null, MaintenanceItemType.LABOR, "Công thay", 1, new BigDecimal("100000.555")))));

        assertThat(quoted.status()).isEqualTo(MaintenanceTicketStatus.QUOTED);
        assertThat(quoted.quote().total()).isEqualByComparingTo("400000.56");

        // A CUSTOM member who did not create the ticket can see it but not approve money.
        UUID strangerId = UUID.randomUUID();
        when(homeAuthorizationService.loadAccessContext(homeId, strangerId)).thenReturn(context(strangerId, HomeRole.MEMBER, AccessScope.CUSTOM));
        when(homeAuthorizationService.effectiveLevel(any(), eq(device))).thenReturn(AccessLevel.VIEW);
        assertThatThrownBy(() -> service.approveQuote(strangerId, homeId, ticket.getId()))
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);

        when(homeAuthorizationService.loadAccessContext(homeId, userId)).thenReturn(context(userId, HomeRole.MEMBER, AccessScope.CUSTOM));
        MaintenanceTicketResponse approved = service.approveQuote(userId, homeId, ticket.getId());
        assertThat(approved.status()).isEqualTo(MaintenanceTicketStatus.REPAIRING);
        assertThat(approved.quote().decision()).isEqualTo(MaintenanceQuoteDecision.APPROVED);
    }

    @Test
    void quoteRejectsLineForUnknownTicketDevice() {
        MaintenanceTicket ticket = ticketWithStatus(MaintenanceTicketStatus.INSPECTING);
        assertThatThrownBy(() -> service.submitQuote(adminId, ticket.getId(), new SubmitMaintenanceQuoteRequest(
                "x", null, List.of(new SubmitMaintenanceQuoteRequest.Item(
                UUID.randomUUID(), MaintenanceItemType.FEE, "Phí", 1, BigDecimal.TEN)))))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_INVALID_REQUEST);
    }

    // ---- complete ----

    @Test
    void completeRequiresOutcomeForEveryDeviceAndResolvesAnomalies() {
        MaintenanceTicket ticket = ticketWithStatus(MaintenanceTicketStatus.REPAIRING);
        UUID lineId = ticket.getDevices().get(0).getId();

        assertThatThrownBy(() -> service.complete(adminId, ticket.getId(),
                new CompleteMaintenanceRequest(List.of(new CompleteMaintenanceRequest.DeviceOutcome(
                        UUID.randomUUID(), MaintenanceDeviceOutcome.FIXED, null)), null)))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_INVALID_REQUEST);

        DeviceAnomaly anomaly = DeviceAnomaly.builder().id(UUID.randomUUID()).device(device)
                .anomalyType(AnomalyType.SENSOR_OFFLINE).status(AnomalyStatus.OPEN).build();
        when(deviceAnomalyRepository.findByDeviceIdAndStatusIn(eq(deviceId), any())).thenReturn(List.of(anomaly));

        MaintenanceTicketResponse done = service.complete(adminId, ticket.getId(),
                new CompleteMaintenanceRequest(List.of(new CompleteMaintenanceRequest.DeviceOutcome(
                        lineId, MaintenanceDeviceOutcome.REPLACED, "Thay mới")), "OK"));

        assertThat(done.status()).isEqualTo(MaintenanceTicketStatus.COMPLETED);
        assertThat(done.devices().get(0).outcome()).isEqualTo(MaintenanceDeviceOutcome.REPLACED);
        assertThat(anomaly.getStatus()).isEqualTo(AnomalyStatus.RESOLVED);
        assertThat(anomaly.isFalsePositive()).isFalse();
        verify(anomalyDetectionService).recordManualResolution(deviceId, AnomalyType.SENSOR_OFFLINE);
    }

    @Test
    void invalidTransitionsAreRejected() {
        MaintenanceTicket ticket = ticketWithStatus(MaintenanceTicketStatus.COMPLETED);
        when(homeAuthorizationService.loadAccessContext(homeId, userId)).thenReturn(context(userId, HomeRole.MEMBER, AccessScope.CUSTOM));

        assertThatThrownBy(() -> service.cancel(userId, homeId, ticket.getId(), null))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_INVALID_STATUS);
        assertThatThrownBy(() -> service.submitQuote(adminId, ticket.getId(), new SubmitMaintenanceQuoteRequest(
                "x", null, List.of(new SubmitMaintenanceQuoteRequest.Item(null, MaintenanceItemType.FEE, "Phí", 1, BigDecimal.ONE)))))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_INVALID_STATUS);

        service.submitFeedback(userId, homeId, ticket.getId(), new MaintenanceFeedbackRequest(5, "Tốt"));
        assertThatThrownBy(() -> service.submitFeedback(userId, homeId, ticket.getId(), new MaintenanceFeedbackRequest(4, null)))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_INVALID_STATUS);
    }

    @Test
    void hiddenTicketLooksNotFound() {
        MaintenanceTicket ticket = ticketWithStatus(MaintenanceTicketStatus.SUBMITTED);
        UUID strangerId = UUID.randomUUID();
        when(homeAuthorizationService.loadAccessContext(homeId, strangerId)).thenReturn(context(strangerId, HomeRole.MEMBER, AccessScope.CUSTOM));
        when(homeAuthorizationService.effectiveLevel(any(), eq(device))).thenReturn(AccessLevel.NONE);

        assertThatThrownBy(() -> service.getForHome(strangerId, homeId, ticket.getId()))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_TICKET_NOT_FOUND);
    }

    @Test
    void createRejectsAttachmentBelongingToAnotherUserOrMoreThanFive() {
        memberWithDeviceLevel(AccessLevel.VIEW);
        UUID attId = UUID.randomUUID();
        User otherUser = User.builder().id(UUID.randomUUID()).fullName("Other").email("other@x.vn").build();
        MaintenanceTicketAttachment foreign = MaintenanceTicketAttachment.builder()
                .id(attId).home(home).uploadedBy(otherUser).storagePublicId("p").url("https://u").contentType("image/jpeg").sizeBytes(100).build();
        when(attachmentRepository.findAllByIdIn(List.of(attId))).thenReturn(List.of(foreign));

        CreateMaintenanceTicketRequest foreignReq = new CreateMaintenanceTicketRequest(
                "Đèn hỏng", null, null, null,
                List.of(new CreateMaintenanceTicketRequest.DeviceItem(deviceId, null, null)),
                List.of(validSlot(1)), null, null, List.of(attId), null);
        assertThatThrownBy(() -> service.create(userId, homeId, foreignReq))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_ATTACHMENT_NOT_FOUND);

        List<UUID> sixIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        CreateMaintenanceTicketRequest tooManyReq = new CreateMaintenanceTicketRequest(
                "Đèn hỏng", null, null, null,
                List.of(new CreateMaintenanceTicketRequest.DeviceItem(deviceId, null, null)),
                List.of(validSlot(1)), null, null, sixIds, null);
        assertThatThrownBy(() -> service.create(userId, homeId, tooManyReq))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_ATTACHMENT_LIMIT_EXCEEDED);
    }

    // ---- fixtures ----

    private void memberWithDeviceLevel(AccessLevel level) {
        when(homeAuthorizationService.loadAccessContext(homeId, userId)).thenReturn(context(userId, HomeRole.MEMBER, AccessScope.CUSTOM));
        when(homeAuthorizationService.effectiveLevel(any(), eq(device))).thenReturn(level);
    }

    private MemberAccessContext context(UUID uid, HomeRole role, AccessScope scope) {
        return new MemberAccessContext(homeId, uid, UUID.randomUUID(), role, MemberStatus.ACTIVE, scope, Map.of(), Map.of());
    }

    private HomeMember member(User u, HomeRole role) {
        HomeMember m = new HomeMember();
        m.setUser(u);
        m.setHome(home);
        m.setRole(role);
        m.setStatus(MemberStatus.ACTIVE);
        return m;
    }

    private MaintenanceTicket ticketWithStatus(MaintenanceTicketStatus status) {
        MaintenanceTicket ticket = new MaintenanceTicket();
        ticket.setId(UUID.randomUUID());
        ticket.setCode("MT-TEST");
        ticket.setHome(home);
        ticket.setCreatedBy(user);
        ticket.setTitle("Đèn hỏng");
        ticket.setStatus(status);
        ticket.addDevice(MaintenanceTicketDevice.builder().id(UUID.randomUUID()).device(device).deviceName("Đèn trần").build());
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(ticketRepository.findByIdAndHomeId(ticket.getId(), homeId)).thenReturn(Optional.of(ticket));
        return ticket;
    }

    private CreateMaintenanceTicketRequest createRequest(MaintenanceTimeSlotRequest slot) {
        return new CreateMaintenanceTicketRequest("Đèn không sáng", "Bật không lên", null, null,
                List.of(new CreateMaintenanceTicketRequest.DeviceItem(deviceId, "Không sáng", null)),
                List.of(slot));
    }

    private MaintenanceTimeSlotRequest validSlot(int daysAhead) {
        return slot(daysAhead, 9, 0, 11, 0);
    }

    private MaintenanceTimeSlotRequest slot(int daysAhead, int sh, int sm, int eh, int em) {
        LocalDate day = LocalDate.ofInstant(NOW, VN).plusDays(daysAhead);
        return new MaintenanceTimeSlotRequest(
                ZonedDateTime.of(day, LocalTime.of(sh, sm), VN).toOffsetDateTime(),
                ZonedDateTime.of(day, LocalTime.of(eh, em), VN).toOffsetDateTime());
    }

    private void assertInvalidSlots(MaintenanceTimeSlotRequest... slots) {
        assertThatThrownBy(() -> service.toValidatedSlots(List.of(slots)))
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_INVALID_SLOT);
    }
}
