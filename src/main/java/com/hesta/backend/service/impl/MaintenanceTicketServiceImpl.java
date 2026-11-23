package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.*;
import com.hesta.backend.dto.response.MaintenanceTicketResponse;
import com.hesta.backend.dto.response.MaintenanceTicketSummaryResponse;
import com.hesta.backend.dto.response.PageResponse;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.event.MaintenanceNotificationEvent;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.AnomalyDetectionService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MaintenanceTicketService;
import com.hesta.backend.service.MemberAccessContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MaintenanceTicketServiceImpl implements MaintenanceTicketService {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final LocalTime WORKDAY_START = LocalTime.of(7, 0);
    static final LocalTime WORKDAY_END = LocalTime.of(21, 0);
    static final Duration MIN_LEAD_TIME = Duration.ofHours(2);
    static final Duration MAX_HORIZON = Duration.ofDays(14);
    static final Duration MIN_SLOT = Duration.ofMinutes(30);
    static final Duration MAX_SLOT = Duration.ofHours(8);
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final Set<AnomalyStatus> UNRESOLVED_ANOMALY = EnumSet.of(AnomalyStatus.OPEN, AnomalyStatus.ACKNOWLEDGED);
    private static final Set<MaintenanceDeviceOutcome> RESOLVING_OUTCOMES = EnumSet.of(
            MaintenanceDeviceOutcome.FIXED, MaintenanceDeviceOutcome.REPLACED, MaintenanceDeviceOutcome.NO_ISSUE_FOUND);

    private final MaintenanceTicketRepository ticketRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceAnomalyRepository deviceAnomalyRepository;
    private final UserRepository userRepository;
    private final HomeMemberRepository homeMemberRepository;
    private final HomeAuthorizationService homeAuthorizationService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // =========================================================================
    // Home user
    // =========================================================================

    @Override
    @Transactional
    public MaintenanceTicketResponse create(UUID userId, UUID homeId, CreateMaintenanceTicketRequest request) {
        Home home = homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext context = homeAuthorizationService.loadAccessContext(homeId, userId);
        User user = requireUser(userId);

        List<CreateMaintenanceTicketRequest.DeviceItem> items = request.devices();
        long distinct = items.stream().map(CreateMaintenanceTicketRequest.DeviceItem::deviceId).distinct().count();
        if (distinct != items.size()) {
            throw new AppException(ErrorCode.MAINTENANCE_INVALID_REQUEST);
        }
        List<MaintenanceAvailabilitySlot> slots = toValidatedSlots(request.slots());

        MaintenanceTicket ticket = new MaintenanceTicket();
        ticket.setCode(generateCode());
        ticket.setHome(home);
        ticket.setCreatedBy(user);
        ticket.setTitle(request.title().trim());
        ticket.setDescription(trimToNull(request.description()));
        ticket.setContactPhone(Optional.ofNullable(trimToNull(request.contactPhone())).orElse(user.getPhoneNumber()));
        ticket.setPriority(request.priority() != null ? request.priority() : MaintenancePriority.NORMAL);
        ticket.setStatus(MaintenanceTicketStatus.SUBMITTED);

        for (CreateMaintenanceTicketRequest.DeviceItem item : items) {
            Device device = requireReportableDevice(context, homeId, item.deviceId());
            ticket.addDevice(MaintenanceTicketDevice.builder()
                    .device(device)
                    .sourceAnomaly(resolveSourceAnomaly(homeId, device, item.anomalyId()))
                    .deviceName(device.getName() != null ? device.getName() : device.getDeviceType())
                    .deviceType(device.getDeviceType())
                    .model(device.getModel())
                    .serialNumber(device.getSerialNumber())
                    .roomName(device.getRoom() != null ? device.getRoom().getName() : null)
                    .issueNote(trimToNull(item.issueNote()))
                    .build());
        }
        ticket.replaceSlots(slots);
        recordEvent(ticket, user, MaintenanceActorRole.USER, null, MaintenanceTicketStatus.SUBMITTED, ticket.getDescription());

        MaintenanceTicket saved = ticketRepository.save(ticket);
        notifyHome(saved, userId, "Yêu cầu bảo trì mới",
                user.getFullName() + " đã gửi yêu cầu kiểm tra " + saved.getCode() + ": " + saved.getTitle() + ".",
                NotificationPriority.LOW);
        log.info("Maintenance ticket {} created in home {} by {}", saved.getCode(), homeId, userId);
        return MaintenanceTicketResponse.from(saved, true);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MaintenanceTicketSummaryResponse> listForHome(
            UUID userId, UUID homeId, MaintenanceTicketStatus status, UUID deviceId, Pageable pageable) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext context = homeAuthorizationService.loadAccessContext(homeId, userId);
        Pageable page = newestFirst(pageable);

        Page<MaintenanceTicket> tickets;
        if (seesWholeHome(context)) {
            tickets = ticketRepository.findForHome(homeId, status, deviceId, page);
        } else {
            Set<UUID> visible = homeAuthorizationService.getVisibleDeviceIds(homeId, userId, AccessLevel.VIEW);
            // JPQL "IN ()" is invalid; a random id never matches.
            Collection<UUID> visibleIds = visible.isEmpty() ? Set.of(UUID.randomUUID()) : visible;
            tickets = ticketRepository.findForMember(homeId, userId, visibleIds, status, deviceId, page);
        }
        return PageResponse.from(tickets.map(MaintenanceTicketSummaryResponse::from));
    }

    @Override
    @Transactional(readOnly = true)
    public MaintenanceTicketResponse getForHome(UUID userId, UUID homeId, UUID ticketId) {
        UserTicket ut = loadVisible(userId, homeId, ticketId);
        return MaintenanceTicketResponse.from(ut.ticket(), ut.canManage());
    }

    @Override
    @Transactional
    public MaintenanceTicketResponse cancel(UUID userId, UUID homeId, UUID ticketId, MaintenanceNoteRequest request) {
        MaintenanceTicket ticket = loadManageable(userId, homeId, ticketId);
        requireStatus(ticket, MaintenanceTicketStatus.SUBMITTED, MaintenanceTicketStatus.SCHEDULED,
                MaintenanceTicketStatus.RESCHEDULE_REQUESTED);
        String reason = noteOf(request);
        ticket.setCancelReason(reason);
        transition(ticket, requireUser(userId), MaintenanceActorRole.USER, MaintenanceTicketStatus.CANCELLED, reason);
        notifyHome(ticket, userId, "Yêu cầu bảo trì đã huỷ",
                "Ticket " + ticket.getCode() + " đã được huỷ.", NotificationPriority.LOW);
        return MaintenanceTicketResponse.from(ticket, true);
    }

    @Override
    @Transactional
    public MaintenanceTicketResponse confirmSchedule(UUID userId, UUID homeId, UUID ticketId) {
        MaintenanceTicket ticket = loadManageable(userId, homeId, ticketId);
        requireStatus(ticket, MaintenanceTicketStatus.SCHEDULED);
        if (!ticket.isScheduleConfirmed()) {
            ticket.setScheduleConfirmed(true);
            recordEvent(ticket, requireUser(userId), MaintenanceActorRole.USER, ticket.getStatus(), ticket.getStatus(),
                    "Người dùng xác nhận lịch hẹn");
        }
        return MaintenanceTicketResponse.from(ticket, true);
    }

    @Override
    @Transactional
    public MaintenanceTicketResponse requestReschedule(
            UUID userId, UUID homeId, UUID ticketId, RescheduleMaintenanceRequest request) {
        MaintenanceTicket ticket = loadManageable(userId, homeId, ticketId);
        requireStatus(ticket, MaintenanceTicketStatus.SCHEDULED);
        ticket.replaceSlots(toValidatedSlots(request.slots()));
        ticket.setScheduledStart(null);
        ticket.setScheduledEnd(null);
        ticket.setScheduleConfirmed(false);
        transition(ticket, requireUser(userId), MaintenanceActorRole.USER,
                MaintenanceTicketStatus.RESCHEDULE_REQUESTED, trimToNull(request.note()));
        return MaintenanceTicketResponse.from(ticket, true);
    }

    @Override
    @Transactional
    public MaintenanceTicketResponse approveQuote(UUID userId, UUID homeId, UUID ticketId) {
        MaintenanceTicket ticket = loadManageable(userId, homeId, ticketId);
        requireStatus(ticket, MaintenanceTicketStatus.QUOTED);
        ticket.setQuoteDecision(MaintenanceQuoteDecision.APPROVED);
        ticket.setQuoteDecidedAt(now());
        transition(ticket, requireUser(userId), MaintenanceActorRole.USER, MaintenanceTicketStatus.REPAIRING,
                "Đồng ý báo giá " + formatMoney(ticket.getQuoteTotal()));
        return MaintenanceTicketResponse.from(ticket, true);
    }

    @Override
    @Transactional
    public MaintenanceTicketResponse rejectQuote(UUID userId, UUID homeId, UUID ticketId, MaintenanceNoteRequest request) {
        MaintenanceTicket ticket = loadManageable(userId, homeId, ticketId);
        requireStatus(ticket, MaintenanceTicketStatus.QUOTED);
        ticket.setQuoteDecision(MaintenanceQuoteDecision.REJECTED);
        ticket.setQuoteDecidedAt(now());
        transition(ticket, requireUser(userId), MaintenanceActorRole.USER, MaintenanceTicketStatus.CLOSED,
                Optional.ofNullable(noteOf(request)).orElse("Từ chối báo giá"));
        return MaintenanceTicketResponse.from(ticket, true);
    }

    @Override
    @Transactional
    public MaintenanceTicketResponse submitFeedback(
            UUID userId, UUID homeId, UUID ticketId, MaintenanceFeedbackRequest request) {
        MaintenanceTicket ticket = loadManageable(userId, homeId, ticketId);
        requireStatus(ticket, MaintenanceTicketStatus.COMPLETED, MaintenanceTicketStatus.CLOSED);
        if (ticket.getRating() != null) {
            throw new AppException(ErrorCode.MAINTENANCE_INVALID_STATUS);
        }
        ticket.setRating(request.rating().shortValue());
        ticket.setFeedback(trimToNull(request.comment()));
        return MaintenanceTicketResponse.from(ticket, true);
    }

    // =========================================================================
    // Platform admin
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MaintenanceTicketSummaryResponse> listForAdmin(
            MaintenanceTicketStatus status, OffsetDateTime from, OffsetDateTime to, Pageable pageable) {
        boolean filterSchedule = from != null || to != null;
        OffsetDateTime lower = from != null ? from : OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime upper = to != null ? to : OffsetDateTime.of(9999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        if (!upper.isAfter(lower)) {
            throw new AppException(ErrorCode.MAINTENANCE_INVALID_REQUEST);
        }
        Page<MaintenanceTicket> tickets = ticketRepository.findForAdmin(
                status, filterSchedule, lower, upper, filterSchedule ? bySchedule(pageable) : newestFirst(pageable));
        return PageResponse.from(tickets.map(MaintenanceTicketSummaryResponse::from));
    }

    @Override
    @Transactional(readOnly = true)
    public MaintenanceTicketResponse getForAdmin(UUID ticketId) {
        return MaintenanceTicketResponse.from(requireTicket(ticketId), false);
    }

    @Override
    @Transactional
    public MaintenanceTicketResponse schedule(UUID adminId, UUID ticketId, ScheduleMaintenanceRequest request) {
        MaintenanceTicket ticket = requireTicket(ticketId);
        requireStatus(ticket, MaintenanceTicketStatus.SUBMITTED, MaintenanceTicketStatus.RESCHEDULE_REQUESTED,
                MaintenanceTicketStatus.SCHEDULED);
        OffsetDateTime start = request.startAt();
        OffsetDateTime end = request.endAt();
        Duration length = Duration.between(start, end);
        if (!start.isAfter(now()) || length.compareTo(MIN_SLOT) < 0 || length.compareTo(MAX_SLOT) > 0) {
            throw new AppException(ErrorCode.MAINTENANCE_INVALID_SLOT);
        }
        if (ticketRepository.existsScheduleOverlap(ticket.getId(), start, end, MaintenanceTicketStatus.OCCUPIES_SCHEDULE)) {
            throw new AppException(ErrorCode.MAINTENANCE_SCHEDULE_CONFLICT);
        }

        // Picking inside one of the user's free slots needs no further confirmation.
        boolean withinUserSlot = ticket.getSlots().stream()
                .anyMatch(s -> !start.isBefore(s.getStartAt()) && !end.isAfter(s.getEndAt()));
        ticket.setScheduledStart(start);
        ticket.setScheduledEnd(end);
        ticket.setScheduleConfirmed(withinUserSlot);
        ticket.setAdminNote(trimToNull(request.note()));
        String when = formatRange(start, end);
        transition(ticket, requireUser(adminId), MaintenanceActorRole.ADMIN, MaintenanceTicketStatus.SCHEDULED,
                withinUserSlot ? "Lịch hẹn " + when : "Đề xuất lịch ngoài khung rảnh: " + when);

        notifyHome(ticket, null, "Đã có lịch kiểm tra thiết bị",
                withinUserSlot
                        ? "Ticket " + ticket.getCode() + ": kỹ thuật viên sẽ đến lúc " + when + "."
                        : "Ticket " + ticket.getCode() + ": đề xuất lịch " + when + ". Vui lòng xác nhận hoặc chọn giờ khác.",
                NotificationPriority.MEDIUM);
        return MaintenanceTicketResponse.from(ticket, false);
    }

    @Override
    @Transactional
    public MaintenanceTicketResponse startInspection(UUID adminId, UUID ticketId, MaintenanceNoteRequest request) {
        MaintenanceTicket ticket = requireTicket(ticketId);
        requireStatus(ticket, MaintenanceTicketStatus.SCHEDULED);
        if (!ticket.isScheduleConfirmed()) {
            throw new AppException(ErrorCode.MAINTENANCE_INVALID_STATUS, "Người dùng chưa xác nhận lịch hẹn");
        }
        transition(ticket, requireUser(adminId), MaintenanceActorRole.ADMIN, MaintenanceTicketStatus.INSPECTING,
                noteOf(request));
        return MaintenanceTicketResponse.from(ticket, false);
    }

    @Override
    @Transactional
    public MaintenanceTicketResponse submitQuote(UUID adminId, UUID ticketId, SubmitMaintenanceQuoteRequest request) {
        MaintenanceTicket ticket = requireTicket(ticketId);
        requireStatus(ticket, MaintenanceTicketStatus.INSPECTING, MaintenanceTicketStatus.QUOTED);
        Map<UUID, MaintenanceTicketDevice> ticketDevices = ticketDevicesById(ticket);

        List<MaintenanceQuoteItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int order = 0;
        for (SubmitMaintenanceQuoteRequest.Item line : request.items()) {
            MaintenanceTicketDevice target = null;
            if (line.ticketDeviceId() != null) {
                target = Optional.ofNullable(ticketDevices.get(line.ticketDeviceId()))
                        .orElseThrow(() -> new AppException(ErrorCode.MAINTENANCE_INVALID_REQUEST));
            }
            BigDecimal unitPrice = line.unitPrice().setScale(2, RoundingMode.HALF_UP);
            // Amount is always computed server-side; the client never sends totals.
            BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(line.quantity())).setScale(2, RoundingMode.HALF_UP);
            total = total.add(amount);
            items.add(MaintenanceQuoteItem.builder()
                    .ticketDevice(target)
                    .itemType(line.itemType())
                    .description(line.description().trim())
                    .quantity(line.quantity())
                    .unitPrice(unitPrice)
                    .amount(amount)
                    .sortOrder(order++)
                    .build());
        }
        ticket.replaceQuoteItems(items);
        ticket.setFindings(request.findings().trim());
        ticket.setWarrantyMonths(request.warrantyMonths());
        ticket.setQuoteTotal(total);
        ticket.setQuoteSubmittedAt(now());
        ticket.setQuoteDecision(null);
        ticket.setQuoteDecidedAt(null);
        transition(ticket, requireUser(adminId), MaintenanceActorRole.ADMIN, MaintenanceTicketStatus.QUOTED,
                "Báo giá " + formatMoney(total));

        notifyHome(ticket, null, "Có báo giá sửa chữa",
                "Ticket " + ticket.getCode() + ": tổng chi phí dự kiến " + formatMoney(total)
                        + ". Vui lòng xem và duyệt báo giá.",
                NotificationPriority.MEDIUM);
        return MaintenanceTicketResponse.from(ticket, false);
    }

    @Override
    @Transactional
    public MaintenanceTicketResponse complete(UUID adminId, UUID ticketId, CompleteMaintenanceRequest request) {
        MaintenanceTicket ticket = requireTicket(ticketId);
        requireStatus(ticket, MaintenanceTicketStatus.INSPECTING, MaintenanceTicketStatus.REPAIRING);
        Map<UUID, MaintenanceTicketDevice> ticketDevices = ticketDevicesById(ticket);

        Map<UUID, CompleteMaintenanceRequest.DeviceOutcome> outcomes = new HashMap<>();
        for (CompleteMaintenanceRequest.DeviceOutcome outcome : request.devices()) {
            if (!ticketDevices.containsKey(outcome.ticketDeviceId())
                    || outcomes.put(outcome.ticketDeviceId(), outcome) != null) {
                throw new AppException(ErrorCode.MAINTENANCE_INVALID_REQUEST);
            }
        }
        if (!outcomes.keySet().equals(ticketDevices.keySet())) {
            throw new AppException(ErrorCode.MAINTENANCE_INVALID_REQUEST, "Cần kết luận cho tất cả thiết bị trong ticket");
        }

        User admin = requireUser(adminId);
        OffsetDateTime completedAt = now();
        for (MaintenanceTicketDevice line : ticket.getDevices()) {
            CompleteMaintenanceRequest.DeviceOutcome outcome = outcomes.get(line.getId());
            line.setOutcome(outcome.outcome());
            line.setOutcomeNote(trimToNull(outcome.note()));
            resolveDeviceAnomalies(line, admin, completedAt, ticket.getCode());
        }
        ticket.setCompletedAt(completedAt);
        transition(ticket, admin, MaintenanceActorRole.ADMIN, MaintenanceTicketStatus.COMPLETED, trimToNull(request.note()));

        notifyHome(ticket, null, "Đã hoàn tất bảo trì",
                "Ticket " + ticket.getCode() + " đã xử lý xong. Xem chi tiết sửa chữa và đánh giá dịch vụ.",
                NotificationPriority.MEDIUM);
        return MaintenanceTicketResponse.from(ticket, false);
    }

    @Override
    @Transactional
    public MaintenanceTicketResponse reject(UUID adminId, UUID ticketId, MaintenanceNoteRequest request) {
        MaintenanceTicket ticket = requireTicket(ticketId);
        requireStatus(ticket, MaintenanceTicketStatus.SUBMITTED, MaintenanceTicketStatus.SCHEDULED,
                MaintenanceTicketStatus.RESCHEDULE_REQUESTED);
        String reason = noteOf(request);
        ticket.setCancelReason(reason);
        transition(ticket, requireUser(adminId), MaintenanceActorRole.ADMIN, MaintenanceTicketStatus.REJECTED, reason);
        notifyHome(ticket, null, "Yêu cầu bảo trì bị từ chối",
                "Ticket " + ticket.getCode() + " bị từ chối" + (reason != null ? ": " + reason : "."),
                NotificationPriority.MEDIUM);
        return MaintenanceTicketResponse.from(ticket, false);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private record UserTicket(MaintenanceTicket ticket, boolean canManage) {
    }

    private UserTicket loadVisible(UUID userId, UUID homeId, UUID ticketId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext context = homeAuthorizationService.loadAccessContext(homeId, userId);
        MaintenanceTicket ticket = ticketRepository.findByIdAndHomeId(ticketId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.MAINTENANCE_TICKET_NOT_FOUND));
        boolean isCreator = ticket.getCreatedBy().getId().equals(userId);
        boolean canView = isCreator || seesWholeHome(context) || ticket.getDevices().stream()
                .map(MaintenanceTicketDevice::getDevice)
                .filter(Objects::nonNull)
                .anyMatch(d -> homeAuthorizationService.effectiveLevel(context, d) != AccessLevel.NONE);
        if (!canView) {
            // Fail closed: do not reveal that the ticket exists.
            throw new AppException(ErrorCode.MAINTENANCE_TICKET_NOT_FOUND);
        }
        return new UserTicket(ticket, isCreator || context.isOwner());
    }

    /** Only the home owner or the ticket creator may change a ticket (cancel, reschedule, approve money). */
    private MaintenanceTicket loadManageable(UUID userId, UUID homeId, UUID ticketId) {
        UserTicket ut = loadVisible(userId, homeId, ticketId);
        if (!ut.canManage()) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return ut.ticket();
    }

    private boolean seesWholeHome(MemberAccessContext context) {
        return context.isOwner() || (context.isActive() && context.scope() == AccessScope.ALL);
    }

    private Device requireReportableDevice(MemberAccessContext context, UUID homeId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .filter(d -> !d.isDeleted())
                .filter(d -> d.getHome() != null && homeId.equals(d.getHome().getId()))
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        if (homeAuthorizationService.effectiveLevel(context, device) == AccessLevel.NONE) {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }
        if (ticketRepository.existsDeviceInStatuses(deviceId, MaintenanceTicketStatus.ACTIVE)) {
            throw new AppException(ErrorCode.MAINTENANCE_DEVICE_HAS_ACTIVE_TICKET,
                    "Thiết bị \"" + device.getName() + "\" đang có ticket bảo trì chưa hoàn tất");
        }
        return device;
    }

    private DeviceAnomaly resolveSourceAnomaly(UUID homeId, Device device, UUID anomalyId) {
        if (anomalyId == null) {
            return null;
        }
        DeviceAnomaly anomaly = deviceAnomalyRepository.findByIdAndHomeId(anomalyId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.ANOMALY_NOT_FOUND));
        if (anomaly.getDevice() == null || !device.getId().equals(anomaly.getDevice().getId())) {
            throw new AppException(ErrorCode.MAINTENANCE_INVALID_REQUEST);
        }
        return anomaly;
    }

    /** A repaired device closes its outstanding anomalies so the dashboard stops flagging it. */
    private void resolveDeviceAnomalies(MaintenanceTicketDevice line, User admin, OffsetDateTime at, String code) {
        if (line.getDevice() == null || !RESOLVING_OUTCOMES.contains(line.getOutcome())) {
            return;
        }
        boolean falsePositive = line.getOutcome() == MaintenanceDeviceOutcome.NO_ISSUE_FOUND;
        for (DeviceAnomaly anomaly : deviceAnomalyRepository.findByDeviceIdAndStatusIn(
                line.getDevice().getId(), UNRESOLVED_ANOMALY)) {
            anomaly.setStatus(AnomalyStatus.RESOLVED);
            anomaly.setResolvedAt(at);
            anomaly.setResolvedBy(admin);
            anomaly.setFalsePositive(falsePositive);
            anomaly.setResolutionNote("Đã xử lý qua ticket bảo trì " + code);
            anomalyDetectionService.recordManualResolution(line.getDevice().getId(), anomaly.getAnomalyType());
        }
    }

    List<MaintenanceAvailabilitySlot> toValidatedSlots(List<MaintenanceTimeSlotRequest> requested) {
        OffsetDateTime now = now();
        OffsetDateTime earliest = now.plus(MIN_LEAD_TIME);
        OffsetDateTime latest = now.plus(MAX_HORIZON);
        List<MaintenanceTimeSlotRequest> sorted = requested.stream()
                .sorted(Comparator.comparing(MaintenanceTimeSlotRequest::startAt))
                .toList();

        List<MaintenanceAvailabilitySlot> result = new ArrayList<>();
        OffsetDateTime previousEnd = null;
        for (MaintenanceTimeSlotRequest slot : sorted) {
            OffsetDateTime start = slot.startAt();
            OffsetDateTime end = slot.endAt();
            Duration length = Duration.between(start, end);
            ZonedDateTime localStart = start.atZoneSameInstant(BUSINESS_ZONE);
            ZonedDateTime localEnd = end.atZoneSameInstant(BUSINESS_ZONE);
            boolean valid = length.compareTo(MIN_SLOT) >= 0
                    && length.compareTo(MAX_SLOT) <= 0
                    && !start.isBefore(earliest)
                    && !end.isAfter(latest)
                    && localStart.toLocalDate().equals(localEnd.toLocalDate())
                    && !localStart.toLocalTime().isBefore(WORKDAY_START)
                    && !localEnd.toLocalTime().isAfter(WORKDAY_END)
                    && (previousEnd == null || !start.isBefore(previousEnd));
            if (!valid) {
                throw new AppException(ErrorCode.MAINTENANCE_INVALID_SLOT);
            }
            previousEnd = end;
            result.add(MaintenanceAvailabilitySlot.builder().startAt(start).endAt(end).build());
        }
        return result;
    }

    private void transition(MaintenanceTicket ticket, User actor, MaintenanceActorRole role,
                            MaintenanceTicketStatus to, String note) {
        MaintenanceTicketStatus from = ticket.getStatus();
        ticket.setStatus(to);
        recordEvent(ticket, actor, role, from, to, note);
    }

    private void recordEvent(MaintenanceTicket ticket, User actor, MaintenanceActorRole role,
                             MaintenanceTicketStatus from, MaintenanceTicketStatus to, String note) {
        ticket.addEvent(MaintenanceTicketEvent.builder()
                .actor(actor)
                .actorRole(role)
                .fromStatus(from)
                .toStatus(to)
                .note(note)
                .createdAt(now())
                .build());
    }

    private void requireStatus(MaintenanceTicket ticket, MaintenanceTicketStatus... allowed) {
        if (!Arrays.asList(allowed).contains(ticket.getStatus())) {
            throw new AppException(ErrorCode.MAINTENANCE_INVALID_STATUS);
        }
    }

    /** Notifies the creator and home owners who are still active members, except the acting user. */
    private void notifyHome(MaintenanceTicket ticket, UUID excludeUserId, String title, String message,
                            NotificationPriority priority) {
        UUID homeId = ticket.getHome().getId();
        UUID creatorId = ticket.getCreatedBy().getId();
        Set<UUID> recipients = homeMemberRepository.findByHomeIdWithUser(homeId).stream()
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE && m.getUser() != null)
                .filter(m -> m.getRole() == HomeRole.OWNER || creatorId.equals(m.getUser().getId()))
                .map(m -> m.getUser().getId())
                .filter(id -> !id.equals(excludeUserId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!recipients.isEmpty()) {
            eventPublisher.publishEvent(new MaintenanceNotificationEvent(homeId, recipients, title, message, priority));
        }
    }

    private Map<UUID, MaintenanceTicketDevice> ticketDevicesById(MaintenanceTicket ticket) {
        return ticket.getDevices().stream()
                .collect(Collectors.toMap(MaintenanceTicketDevice::getId, d -> d));
    }

    private MaintenanceTicket requireTicket(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new AppException(ErrorCode.MAINTENANCE_TICKET_NOT_FOUND));
    }

    private User requireUser(UUID userId) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return userRepository.findById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private String generateCode() {
        // shortcut: 6 hex chars per day (~16M combinations) backed by the UNIQUE constraint; switch to a DB sequence if volume grows.
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        return "MT-" + LocalDate.now(clock.withZone(BUSINESS_ZONE)).format(CODE_DATE) + "-" + suffix;
    }

    private Pageable newestFirst(Pageable pageable) {
        return PageRequest.of(Math.max(pageable.getPageNumber(), 0), Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private Pageable bySchedule(Pageable pageable) {
        return PageRequest.of(Math.max(pageable.getPageNumber(), 0), Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.asc("scheduledStart"), Sort.Order.asc("id")));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String noteOf(MaintenanceNoteRequest request) {
        return request == null ? null : trimToNull(request.note());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String formatRange(OffsetDateTime start, OffsetDateTime end) {
        ZonedDateTime s = start.atZoneSameInstant(BUSINESS_ZONE);
        ZonedDateTime e = end.atZoneSameInstant(BUSINESS_ZONE);
        return s.format(DISPLAY_TIME) + " - " + e.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "0 ₫";
        }
        return String.format(Locale.forLanguageTag("vi-VN"), "%,.0f ₫", amount);
    }
}
