package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.ai.AiProperties;
import com.hesta.backend.dto.request.CreateMaintenanceTicketRequest;
import com.hesta.backend.dto.response.MaintenanceTicketResponse;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.maintenance.playbook.PlaybookDefinition;
import com.hesta.backend.maintenance.playbook.PlaybookService;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MaintenanceAttachmentService;
import com.hesta.backend.service.MaintenanceRateLimiter;
import com.hesta.backend.service.MaintenanceTicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Giai đoạn 1: Máy trạng thái chẩn đoán có cấu trúc (chưa gọi LLM).
 * Backend là nguồn sự thật duy nhất; ticket chỉ được tạo qua confirmTicket() gọi lại MaintenanceTicketService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaintenanceAssistantServiceImpl implements MaintenanceAssistantService {

    public static final String ACTION_STEP_OK = "STEP_OK";
    public static final String ACTION_STEP_NOT_OK = "STEP_NOT_OK";
    public static final String ACTION_SAFETY_HAZARD = "SAFETY_HAZARD";
    public static final String ACTION_ESCALATE_NOW = "ACTION_ESCALATE_NOW";
    public static final String ACTION_KEEP_CURRENT_STEP = "KEEP_CURRENT_STEP";
    public static final String ACTION_SWITCH_SYMPTOM_PREFIX = "SWITCH_SYMPTOM:";
    public static final String ACTION_CAUTION_YES = "CAUTION_YES";
    public static final String ACTION_CAUTION_UNSURE = "CAUTION_UNSURE";
    public static final String ACTION_CAUTION_NO = "CAUTION_NO";
    public static final String ACTION_IMAGE_CONSENT_ACCEPT = "IMAGE_CONSENT_ACCEPT";
    public static final String ACTION_IMAGE_CONSENT_DECLINE = "IMAGE_CONSENT_DECLINE";
    public static final String ACTION_REMOVE_IMAGE_PREFIX = "REMOVE_IMAGE:";
    public static final String ACTION_CONFIRM_IMAGE_SYMPTOM_PREFIX = "CONFIRM_IMAGE_SYMPTOM:";
    public static final String ACTION_REJECT_IMAGE_SYMPTOM = "REJECT_IMAGE_SYMPTOM";
    public static final String ACTION_RECHECK_STATUS = "RECHECK_STATUS";

    private final HomeAuthorizationService homeAuthorizationService;
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final MaintenanceChatConversationRepository conversationRepository;
    private final MaintenanceChatMessageRepository messageRepository;
    private final MaintenanceTicketRepository ticketRepository;
    private final DeviceDiagnosticContextService diagnosticContextService;
    private final PlaybookService playbookService;
    private final MaintenanceTicketService maintenanceTicketService;
    private final MaintenanceAttachmentService maintenanceAttachmentService;
    private final MaintenanceRateLimiter rateLimiter;
    private final SafetySignalDetector safetySignalDetector;
    private final MaintenanceTextInterpreter textInterpreter;
    private final TextInterpretationHandler textInterpretationHandler;
    private final MaintenanceTicketSummarizer ticketSummarizer;
    private final ImageSanitizer imageSanitizer;
    private final MaintenanceImageAnalyzer imageAnalyzer;
    private final ImageAnalysisHandler imageAnalysisHandler;
    private final AiProperties aiProperties;
    private final MaintenanceAssistantRolloutService rolloutService;
    private final MaintenanceChatEventRepository eventRepository;

    @Override
    @Transactional
    public MaintenanceAssistantDtos.ConversationResponse startConversation(
            UUID userId, UUID homeId, MaintenanceAssistantDtos.StartConversationRequest request) {
        Home home = homeAuthorizationService.requireAccess(userId, homeId);
        User user = requireUser(userId);
        long activeCount = conversationRepository.countByUserIdAndStatus(userId, MaintenanceChatStatus.ACTIVE);
        rateLimiter.checkActiveConversations(userId, activeCount);

        MaintenanceChatConversation conv = MaintenanceChatConversation.builder()
                .home(home)
                .user(user)
                .state(MaintenanceChatState.SELECT_DEVICE)
                .status(MaintenanceChatStatus.ACTIVE)
                .contextJson(new LinkedHashMap<>())
                .build();
        conv = conversationRepository.save(conv);

        List<Map<String, Object>> blocks;
        UUID deviceId = request != null ? request.deviceId() : null;
        if (deviceId != null) {
            blocks = attachDeviceAndInspect(conv, userId, homeId, deviceId);
        } else {
            blocks = buildSelectDeviceBlocks(homeId, userId);
        }

        saveMessage(conv, MaintenanceChatRole.ASSISTANT, blocks);
        conv = conversationRepository.save(conv);
        return toConversationResponse(conv, messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public MaintenanceAssistantDtos.ConversationResponse getConversation(UUID userId, UUID homeId, UUID conversationId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MaintenanceChatConversation conv = requireOwnedConversation(conversationId, homeId, userId);
        List<MaintenanceChatMessage> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.getId());
        return toConversationResponse(conv, messages);
    }

    @Override
    @Transactional(readOnly = true)
    public MaintenanceAssistantDtos.ConversationResponse getLatestActiveConversation(UUID userId, UUID homeId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        List<MaintenanceChatConversation> active = conversationRepository
                .findByHomeIdAndUserIdAndStatusOrderByUpdatedAtDesc(homeId, userId, MaintenanceChatStatus.ACTIVE);
        if (active.isEmpty()) {
            return null;
        }
        MaintenanceChatConversation conv = active.get(0);
        return toConversationResponse(conv, messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.getId()));
    }

    @Override
    @Transactional
    public MaintenanceAssistantDtos.MessageReplyResponse sendMessage(
            UUID userId, UUID homeId, UUID conversationId, MaintenanceAssistantDtos.SendMessageRequest request) {
        homeAuthorizationService.requireAccess(userId, homeId);
        String value = request.value().trim();
        boolean isRecheckAction = request.type() == MaintenanceAssistantDtos.MessageInputType.QUICK_REPLY
                && ACTION_RECHECK_STATUS.equals(value);
        if (!isRecheckAction) {
            rateLimiter.checkAndConsumeMessage(userId);
        }

        MaintenanceChatConversation conv = requireOwnedConversation(conversationId, homeId, userId);
        if (conv.getStatus() != MaintenanceChatStatus.ACTIVE) {
            throw new AppException(ErrorCode.MAINTENANCE_CHAT_INVALID_STATE,
                    "Phiên chẩn đoán này đã kết thúc. Vui lòng bắt đầu phiên mới nếu cần hỗ trợ thêm.");
        }

        Map<String, Object> ctx = ensureContext(conv);
        String imageCaption = request.note() != null ? request.note().trim() : "";
        if (request.type() == MaintenanceAssistantDtos.MessageInputType.IMAGE) {
            Map<String, Object> imgBlock = new LinkedHashMap<>();
            imgBlock.put("type", "image");
            imgBlock.put("attachmentId", value);
            imgBlock.put("url", "/api/v1/homes/" + homeId + "/maintenance-attachments/" + value + "/content");
            imgBlock.put("caption", "Ảnh thiết bị");
            List<Map<String, Object>> userBlocks = new ArrayList<>();
            userBlocks.add(imgBlock);
            if (!imageCaption.isBlank()) {
                userBlocks.add(textBlock(imageCaption, "default"));
            }
            saveMessage(conv, MaintenanceChatRole.USER, userBlocks);
        } else {
            String displayUserText = resolveUserDisplayText(conv, request.type(), value, ctx);
            saveMessage(conv, MaintenanceChatRole.USER, List.of(textBlock(displayUserText, "default")));
        }

        List<Map<String, Object>> replyBlocks;

        if (conv.getState() == MaintenanceChatState.SELECT_DEVICE) {
            if (request.type() == MaintenanceAssistantDtos.MessageInputType.QUICK_REPLY) {
                UUID chosenDeviceId = parseUuidOrThrow(value);
                replyBlocks = attachDeviceAndInspect(conv, userId, homeId, chosenDeviceId);
                // Đồng bộ lại ctx sau attachDeviceAndInspect để không ghi đè mất deviceName/deviceType/deviceStatus
                ctx.putAll(ensureContext(conv));
            } else {
                appendUserNote(ctx, value);
                replyBlocks = new ArrayList<>();
                replyBlocks.add(textBlock("Mình đã ghi nhận mô tả của bạn. Hãy chọn thiết bị đang gặp sự cố bên dưới để bắt đầu kiểm tra nhé.", "info"));
                replyBlocks.addAll(buildSelectDeviceBlocks(homeId, userId));
            }
        } else {
            backfillDeviceContextIfMissing(conv, ctx);
            PlaybookDefinition playbook = playbookService.getForDeviceType(stringVal(ctx.get("deviceType")));

            // LỚP AN TOÀN 1: chạy TRƯỚC và độc lập hoàn toàn với LLM (có dấu & không dấu)
            String textToCheckSafety = request.type() == MaintenanceAssistantDtos.MessageInputType.TEXT
                    ? value
                    : (request.type() == MaintenanceAssistantDtos.MessageInputType.IMAGE ? imageCaption : "");
            List<String> layer1Signals = !textToCheckSafety.isBlank()
                    ? safetySignalDetector.detect(textToCheckSafety)
                    : List.of();
            boolean isSafetyTrigger = ACTION_SAFETY_HAZARD.equals(value)
                    || (playbook.getSafety() != null && value.equals(playbook.getSafety().getTriggerId()))
                    || !layer1Signals.isEmpty()
                    || (!textToCheckSafety.isBlank() && playbookService.matchesSafetyKeyword(playbook, textToCheckSafety));

            if (isSafetyTrigger) {
                ctx.put("layer1SafetyFlag", true);
                if (!layer1Signals.isEmpty()) {
                    ctx.put("safetySignals", layer1Signals);
                }
                if (!textToCheckSafety.isBlank()) {
                    appendUserNote(ctx, textToCheckSafety);
                }
                recordMetricEvent(conv, "SAFETY_LAYER1", Map.of("source", request.type().name()));
                replyBlocks = enterSafetyEscalation(conv, userId, ctx, playbook);
            } else if (isStaleGuidedStep(conv, ctx, playbook)) {
                conv.setState(MaintenanceChatState.SELECT_SYMPTOM);
                ctx.remove("symptomId");
                ctx.remove("currentStepIndex");
                boolean validQuickRecovery = request.type() == MaintenanceAssistantDtos.MessageInputType.QUICK_REPLY
                        && (findSymptom(playbook, value) != null
                        || ACTION_ESCALATE_NOW.equals(value)
                        || ACTION_RECHECK_STATUS.equals(value));
                if (validQuickRecovery) {
                    replyBlocks = handleQuickReply(conv, userId, homeId, ctx, playbook, value);
                } else {
                    replyBlocks = List.of(
                            textBlock("Hướng dẫn chẩn đoán cho thiết bị này vừa được cập nhật. Bạn vui lòng chọn lại triệu chứng bên dưới để tiếp tục nhé.", "info"),
                            symptomQuickReplies(playbook, "OFFLINE".equals(ctx.get("deviceStatus")))
                    );
                }
            } else if (request.type() == MaintenanceAssistantDtos.MessageInputType.IMAGE) {
                replyBlocks = handleImageMessage(conv, userId, homeId, ctx, playbook, parseUuidOrThrow(value), imageCaption);
            } else if (request.type() == MaintenanceAssistantDtos.MessageInputType.TEXT) {
                replyBlocks = handleFreeText(conv, userId, ctx, playbook, value);
            } else {
                replyBlocks = handleQuickReply(conv, userId, homeId, ctx, playbook, value);
            }
        }

        conv.setContextJson(new LinkedHashMap<>(ctx));
        saveMessage(conv, MaintenanceChatRole.ASSISTANT, replyBlocks);
        conversationRepository.save(conv);

        MaintenanceAssistantRolloutService.HomeCapabilities caps = rolloutService.resolveForHome(homeId);
        return new MaintenanceAssistantDtos.MessageReplyResponse(
                conv.getId(),
                conv.getState(),
                conv.getStatus(),
                conv.getTicket() != null ? conv.getTicket().getId() : null,
                buildManualFallbackNote(ctx),
                caps.aiTextEnabled(),
                caps.aiImageEnabled(),
                replyBlocks
        );
    }

    @Override
    @Transactional
    public MaintenanceAssistantDtos.ConfirmTicketResponse confirmTicket(
            UUID userId, UUID homeId, UUID conversationId, MaintenanceAssistantDtos.ConfirmTicketRequest request) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MaintenanceChatConversation conv = requireOwnedConversation(conversationId, homeId, userId);

        if (conv.getStatus() != MaintenanceChatStatus.ACTIVE || conv.getTicket() != null
                || conv.getState() == MaintenanceChatState.TICKET_CREATED) {
            throw new AppException(ErrorCode.MAINTENANCE_CHAT_INVALID_STATE,
                    "Phiên hội thoại này đã tạo ticket hoặc đã kết thúc, không thể tạo thêm ticket.");
        }
        if (conv.getDevice() == null) {
            throw new AppException(ErrorCode.MAINTENANCE_CHAT_INVALID_STATE,
                    "Chưa chọn thiết bị cho phiên chẩn đoán.");
        }

        Map<String, Object> ctx = ensureContext(conv);
        backfillDeviceContextIfMissing(conv, ctx);
        if (request.extraNote() != null && !request.extraNote().isBlank()) {
            appendUserNote(ctx, request.extraNote().trim());
        }
        if (request.editedSummary() != null && !request.editedSummary().isBlank()) {
            String sanitized = sanitizePlainText(request.editedSummary(), 1500);
            if (!sanitized.isBlank()) {
                ctx.put("editedSummary", sanitized);
            }
        }

        boolean safetyFlag = Boolean.TRUE.equals(ctx.get("safetyFlag")) || Boolean.TRUE.equals(ctx.get("layer1SafetyFlag"));
        MaintenancePriority priority = resolveTicketPriority(ctx, safetyFlag);
        String deviceName = stringVal(ctx.get("deviceName"));
        String title = buildTicketTitle(deviceName, safetyFlag);
        String diagnosisSummary = buildDiagnosisSummary(ctx);
        String issueNote = buildDeviceIssueNote(ctx);
        UUID sourceAnomalyId = parseUuidOrNull(stringVal(ctx.get("firstAnomalyId")));

        CreateMaintenanceTicketRequest createReq = new CreateMaintenanceTicketRequest(
                title,
                buildTicketDescription(ctx),
                request.contactPhone(),
                priority,
                List.of(new CreateMaintenanceTicketRequest.DeviceItem(
                        conv.getDevice().getId(),
                        issueNote,
                        sourceAnomalyId)),
                request.slots(),
                MaintenanceTicketSource.CHATBOT,
                diagnosisSummary,
                request.attachmentIds(),
                conv.getId(),
                buildImageObservationsSection(ctx)
        );

        // Gọi lại ĐÚNG service tạo ticket hiện có để tái sử dụng toàn bộ validate (slot, quyền, attachment, notification)
        MaintenanceTicketResponse created = maintenanceTicketService.create(userId, homeId, createReq);

        MaintenanceTicket ticketRef = ticketRepository.findById(created.id()).orElse(null);
        conv.setTicket(ticketRef);
        conv.setState(MaintenanceChatState.TICKET_CREATED);
        conv.setStatus(MaintenanceChatStatus.ESCALATED);
        conv.setContextJson(new LinkedHashMap<>(ctx));

        List<Map<String, Object>> blocks = List.of(
                textBlock("Đã tạo yêu cầu bảo trì " + created.code() + " thành công. Kỹ thuật viên sẽ xem tóm tắt chẩn đoán và sắp xếp lịch hẹn cho bạn.", "ok"),
                ticketCreatedBlock(created.id(), created.code(), created.title(), created.status(), created.priority(), false)
        );
        saveMessage(conv, MaintenanceChatRole.ASSISTANT, blocks);
        conversationRepository.save(conv);

        return new MaintenanceAssistantDtos.ConfirmTicketResponse(
                conv.getId(),
                conv.getState(),
                conv.getStatus(),
                created,
                blocks
        );
    }

    @Override
    @Transactional
    public void abandonConversation(UUID userId, UUID homeId, UUID conversationId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MaintenanceChatConversation conv = requireOwnedConversation(conversationId, homeId, userId);
        if (conv.getStatus() == MaintenanceChatStatus.ACTIVE) {
            conv.setStatus(MaintenanceChatStatus.ABANDONED);
            conversationRepository.save(conv);
        }
    }

    @Override
    @Transactional
    public MaintenanceAssistantDtos.ConversationFeedbackResponse submitFeedback(
            UUID userId, UUID homeId, UUID conversationId, MaintenanceAssistantDtos.ConversationFeedbackRequest request) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MaintenanceChatConversation conv = requireOwnedConversation(conversationId, homeId, userId);
        boolean finished = conv.getStatus() == MaintenanceChatStatus.RESOLVED
                || conv.getStatus() == MaintenanceChatStatus.ESCALATED
                || conv.getState() == MaintenanceChatState.TICKET_CREATED;
        if (!finished) {
            throw new AppException(ErrorCode.MAINTENANCE_CHAT_INVALID_STATE,
                    "Chỉ có thể đánh giá sau khi phiên chẩn đoán đã kết thúc.");
        }
        if (conv.getUserRating() != null) {
            throw new AppException(ErrorCode.MAINTENANCE_CHAT_INVALID_STATE,
                    "Bạn đã gửi đánh giá cho phiên chẩn đoán này rồi.");
        }
        Integer rating = request != null ? request.rating() : null;
        if (rating == null || (rating != 1 && rating != 5)) {
            throw new AppException(ErrorCode.MAINTENANCE_INVALID_REQUEST,
                    "Mức đánh giá chỉ chấp nhận 1 (không hữu ích) hoặc 5 (hữu ích).");
        }
        String sanitized = request.note() != null ? sanitizePlainText(request.note(), 500) : "";
        conv.setUserRating(rating.shortValue());
        conv.setUserFeedbackNote(sanitized.isBlank() ? null : sanitized);
        conversationRepository.save(conv);
        return new MaintenanceAssistantDtos.ConversationFeedbackResponse(
                conv.getId(), rating, conv.getUserFeedbackNote());
    }

    @Override
    @Transactional
    public void deleteConversation(UUID userId, UUID homeId, UUID conversationId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MaintenanceChatConversation conv = requireOwnedConversation(conversationId, homeId, userId);
        Map<String, Object> ctx = ensureContext(conv);
        for (String attIdStr : listOfStrings(ctx.get("conversationAttachmentIds"))) {
            UUID attId = parseUuidOrNull(attIdStr);
            if (attId != null) {
                try {
                    maintenanceAttachmentService.deleteOwnedUnattached(userId, homeId, attId);
                } catch (Exception ignored) {
                    // Already attached to ticket or already deleted
                }
            }
        }
        messageRepository.deleteByConversationId(conv.getId());
        if (conv.getTicket() == null) {
            conversationRepository.delete(conv);
        } else {
            ctx.remove("userNotes");
            conv.setContextJson(new LinkedHashMap<>(ctx));
            conversationRepository.save(conv);
        }
    }

    // =========================================================================
    // State machine transitions
    // =========================================================================

    private List<Map<String, Object>> buildSelectDeviceBlocks(UUID homeId, UUID userId) {
        List<Device> devices = diagnosticContextService.listAccessibleDevices(homeId, userId);
        List<Map<String, Object>> deviceItems = devices.stream().map(d -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", d.getId().toString());
            item.put("name", d.getName() != null ? d.getName() : d.getDeviceType());
            item.put("deviceType", d.getDeviceType());
            item.put("roomName", d.getRoom() != null ? d.getRoom().getName() : null);
            item.put("model", d.getModel());
            item.put("status", d.getStatus() != null ? d.getStatus().name() : "UNKNOWN");
            return item;
        }).toList();

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(textBlock("Chào bạn! Mình là trợ lý chẩn đoán bảo trì Hesta. Hãy chọn thiết bị đang gặp sự cố để mình kiểm tra nhanh trạng thái nhé.", "default"));
        Map<String, Object> picker = new LinkedHashMap<>();
        picker.put("type", "device_picker");
        picker.put("devices", deviceItems);
        blocks.add(picker);
        return blocks;
    }

    private List<Map<String, Object>> attachDeviceAndInspect(
            MaintenanceChatConversation conv, UUID userId, UUID homeId, UUID deviceId) {
        DeviceDiagnosticContext diag = diagnosticContextService.buildContext(homeId, deviceId, userId);
        Device deviceRef = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        conv.setDevice(deviceRef);

        Map<String, Object> ctx = ensureContext(conv);
        ctx.put("deviceId", diag.deviceId().toString());
        ctx.put("deviceName", diag.name());
        ctx.put("deviceType", diag.deviceType());
        ctx.put("model", diag.model());
        ctx.put("roomName", diag.roomName());
        ctx.put("deviceStatus", diag.status() != null ? diag.status().name() : "UNKNOWN");
        ctx.put("offlineDurationLabel", diag.offlineDurationLabel());
        ctx.put("currentStateSummary", diag.currentStateSummary());
        if (diag.openAnomalies() != null && !diag.openAnomalies().isEmpty()) {
            ctx.put("firstAnomalyId", diag.openAnomalies().get(0).id().toString());
            ctx.put("openAnomalySummary", diag.openAnomalies().stream()
                    .map(a -> a.anomalyType() + (a.reason() != null ? " (" + a.reason() + ")" : ""))
                    .collect(Collectors.joining("; ")));
        }

        conv.setContextJson(ctx);

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(deviceCardBlock(diag));

        // Nếu thiết bị đã có ticket đang hoạt động -> báo ngay mã ticket, KHÔNG đi tiếp chẩn đoán
        if (diag.activeTicket() != null) {
            MaintenanceTicket existingTicket = ticketRepository.findById(diag.activeTicket().id()).orElse(null);
            conv.setTicket(existingTicket);
            conv.setState(MaintenanceChatState.TICKET_CREATED);
            conv.setStatus(MaintenanceChatStatus.ESCALATED);
            blocks.add(textBlock(
                    "Thiết bị \"" + diag.name() + "\" hiện đã có yêu cầu bảo trì đang xử lý (" + diag.activeTicket().code()
                            + "). Bạn có thể xem chi tiết tiến trình bên dưới mà không cần chẩn đoán lại.",
                    "info"));
            blocks.add(ticketCreatedBlock(
                    diag.activeTicket().id(),
                    diag.activeTicket().code(),
                    diag.activeTicket().title(),
                    diag.activeTicket().status(),
                    MaintenancePriority.NORMAL,
                    true));
            return blocks;
        }

        // Mở đầu thông minh dựa trên context thiết bị
        StringBuilder intro = new StringBuilder("Mình đã kiểm tra nhanh trạng thái của \"").append(diag.name()).append("\". ");
        if (diag.homeWideOutage()) {
            recordMetricEvent(conv, "HOME_WIDE_OUTAGE", Map.of(
                    "total", diag.homeTotalDevices(),
                    "offline", diag.homeOfflineDevices()));
            intro.append("Cảnh báo diện rộng: hiện có ")
                    .append(diag.homeOfflineDevices()).append("/").append(diag.homeTotalDevices())
                    .append(" thiết bị trong nhà đang mất kết nối — rất có thể sự cố nằm ở router Wi-Fi hoặc bộ điều khiển trung tâm (gateway/Edge Node). ")
                    .append("Bạn hãy kiểm tra đèn Wi-Fi/Internet trên router và đèn nguồn/mạng trên Edge Node trước, KHÔNG cần tạo nhiều ticket riêng lẻ cho từng thiết bị (nếu vẫn lỗi sau các bước kiểm tra, hãy dùng form thủ công để gộp chung các thiết bị vào 1 ticket duy nhất). ");
        } else if (diag.status() == DeviceStatus.OFFLINE) {
            intro.append("Thiết bị hiện đang mất kết nối");
            if (diag.offlineDurationLabel() != null) {
                intro.append(" khoảng ").append(diag.offlineDurationLabel());
            }
            intro.append(" — bạn nên ưu tiên kiểm tra nguồn điện và Wi-Fi/gateway trước. ");
        } else if (diag.status() == DeviceStatus.ERROR) {
            intro.append("Thiết bị đang ở trạng thái LỖI. ");
        }
        if (diag.openAnomalies() != null && !diag.openAnomalies().isEmpty()) {
            intro.append("Hệ thống cũng ghi nhận cảnh báo bất thường đang mở: ")
                    .append(diag.openAnomalies().get(0).reason() != null
                            ? diag.openAnomalies().get(0).reason()
                            : diag.openAnomalies().get(0).anomalyType())
                    .append(". ");
        }
        if (diag.health() != null && diag.health().chatteringWarning()) {
            intro.append("Lưu ý: thiết bị có dấu hiệu đóng cắt relay bất thường (chattering). ");
        }
        intro.append("Bạn hãy chọn triệu chứng gần nhất bên dưới:");

        conv.setState(MaintenanceChatState.SELECT_SYMPTOM);
        PlaybookDefinition playbook = playbookService.getForDeviceType(diag.deviceType());
        if (playbook.getOpeningWarning() != null && !playbook.getOpeningWarning().isBlank()) {
            blocks.add(textBlock(playbook.getOpeningWarning(), "warn"));
        }
        blocks.add(textBlock(intro.toString(), diag.homeWideOutage() ? "warn" : "default"));
        boolean shortOffline = diag.status() == DeviceStatus.OFFLINE
                && diag.offlineDurationMinutes() != null
                && diag.offlineDurationMinutes() < 30;
        if (shortOffline) {
            List<Map<String, Object>> opts = new ArrayList<>();
            opts.add(quickReplyOption(ACTION_RECHECK_STATUS, "Kiểm tra lại trạng thái", "primary"));
            opts.addAll(symptomQuickReplyOptions(playbook, true));
            blocks.add(quickRepliesBlock(opts));
        } else {
            blocks.add(symptomQuickReplies(playbook, diag.status() == DeviceStatus.OFFLINE));
        }
        return blocks;
    }

    private List<Map<String, Object>> handleFreeText(
            MaintenanceChatConversation conv, UUID userId, Map<String, Object> ctx,
            PlaybookDefinition playbook, String value) {
        UUID homeId = conv.getHome() != null ? conv.getHome().getId() : null;
        if (!rolloutService.isAiTextEnabled(homeId)
                || (conv.getState() != MaintenanceChatState.SELECT_SYMPTOM
                && conv.getState() != MaintenanceChatState.GUIDED_STEP
                && conv.getState() != MaintenanceChatState.SHOW_STATUS)) {
            appendUserNote(ctx, value);
            return handleFreeTextFallback(conv, ctx, playbook, false);
        }

        int maxCalls = aiProperties.getTextInterpreter().getMaxLlmCallsPerConversation();
        if (conv.getLlmCallCount() >= maxCalls || !rateLimiter.tryConsumeLlmCall(userId)) {
            recordMetricEvent(conv, "LLM_FALLBACK", Map.of("reason", "QUOTA"));
            appendUserNote(ctx, value);
            return handleFreeTextFallback(conv, ctx, playbook, true);
        }

        List<MaintenanceChatMessage> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.getId());
        String lastBotQuestion = extractLastAssistantText(history);
        List<String> recentTurns = extractRecentTurns(history);
        String healthAnomaly = buildBriefHealthAnomaly(ctx);

        Optional<TextInterpretationModels.RawInterpretation> rawOpt = textInterpreter.interpret(
                new MaintenanceTextInterpreter.InterpretInput(
                        conv.getId(),
                        conv.getState(),
                        stringVal(ctx.get("deviceType")),
                        stringVal(ctx.get("model")),
                        stringVal(ctx.get("roomName")),
                        stringVal(ctx.get("deviceStatus")),
                        stringVal(ctx.get("offlineDurationLabel")),
                        healthAnomaly,
                        playbook,
                        stringVal(ctx.get("symptomId")),
                        lastBotQuestion,
                        recentTurns,
                        value
                ));

        if (rawOpt.isEmpty()) {
            recordMetricEvent(conv, "LLM_FALLBACK", Map.of("reason", "ERROR"));
            appendUserNote(ctx, value);
            return handleFreeTextFallback(conv, ctx, playbook, true);
        }

        TextInterpretationModels.RawInterpretation raw = rawOpt.get();
        conv.setLlmCallCount(conv.getLlmCallCount() + 1);
        conv.setLlmTotalTokens(conv.getLlmTotalTokens() + Math.max(0, raw.totalTokens()));
        if (raw.confidence() < aiProperties.getTextInterpreter().getConfidenceThreshold()) {
            recordMetricEvent(conv, "LLM_LOW_CONFIDENCE", Map.of("action", raw.action().name()));
        }

        int consecutiveClarifies = intVal(ctx.get("consecutiveClarifies"), 0);
        TextInterpretationModels.HandledDecision decision = textInterpretationHandler.evaluate(
                raw, conv.getState(), playbook, stringVal(ctx.get("symptomId")), consecutiveClarifies);

        if (decision.extractedDetails() != null && !decision.extractedDetails().isBlank()) {
            appendExtractedDetail(ctx, decision.extractedDetails());
        }

        return switch (decision.decision()) {
            case SAFETY_HAZARD -> {
                ctx.put("consecutiveClarifies", 0);
                if (decision.safetySignals() != null && !decision.safetySignals().isEmpty()) {
                    ctx.put("safetySignals", decision.safetySignals());
                }
                appendUserNote(ctx, value);
                recordMetricEvent(conv, "SAFETY_LAYER2", Map.of("kind", "TEXT"));
                recordMetricEvent(conv, "CAUTION_PROMPTED", Map.of("kind", "TEXT"));
                // Bước 3.0b: Lớp 1 KHÔNG khớp nhưng LLM nghi ngờ nguy hiểm -> hỏi xác nhận mức CAUTION, KHÔNG ép URGENT ngay
                yield askCautionConfirmation(ctx, decision.safetySignals());
            }
            case SELECT_SYMPTOM -> {
                ctx.put("consecutiveClarifies", 0);
                yield handleQuickReply(conv, userId, conv.getHome().getId(), ctx, playbook, decision.symptomId());
            }
            case CONFIRM_SWITCH_SYMPTOM -> {
                ctx.put("consecutiveClarifies", 0);
                String newLabel = playbook.getSymptoms().stream()
                        .filter(s -> s.getId().equals(decision.symptomId()))
                        .map(PlaybookDefinition.Symptom::getLabel)
                        .findFirst().orElse(decision.symptomId());
                List<Map<String, Object>> opts = List.of(
                        quickReplyOption(ACTION_SWITCH_SYMPTOM_PREFIX + decision.symptomId(), "Đúng, chuyển sang: " + newLabel, "primary"),
                        quickReplyOption(ACTION_KEEP_CURRENT_STEP, "Không, tiếp tục bước hiện tại", "default")
                );
                yield List.of(
                        textBlock("Bạn có muốn chuyển sang kiểm tra triệu chứng \"" + newLabel + "\" không?", "info", true),
                        quickRepliesBlock(opts)
                );
            }
            case ANSWER_STEP_RESOLVED -> {
                ctx.put("consecutiveClarifies", 0);
                yield handleQuickReply(conv, userId, conv.getHome().getId(), ctx, playbook, ACTION_STEP_OK);
            }
            case ANSWER_STEP_NOT_RESOLVED -> {
                ctx.put("consecutiveClarifies", 0);
                yield handleQuickReply(conv, userId, conv.getHome().getId(), ctx, playbook, ACTION_STEP_NOT_OK);
            }
            case ESCALATE_REQUEST -> {
                ctx.put("consecutiveClarifies", 0);
                appendUserNote(ctx, value);
                yield enterNormalEscalation(conv, userId, ctx,
                        "Mình đã ghi nhận yêu cầu hỗ trợ kỹ thuật của bạn. Hãy xem lại thông tin và chọn khung giờ rảnh bên dưới để xác nhận tạo yêu cầu bảo trì nhé.");
            }
            case ASK_CLARIFY -> {
                ctx.put("consecutiveClarifies", consecutiveClarifies + 1);
                List<Map<String, Object>> blocks = new ArrayList<>();
                blocks.add(textBlock(decision.clarifyQuestion(), "info", true));
                if (conv.getState() == MaintenanceChatState.SELECT_SYMPTOM) {
                    blocks.add(symptomQuickReplies(playbook, "OFFLINE".equals(ctx.get("deviceStatus"))));
                } else if (conv.getState() == MaintenanceChatState.GUIDED_STEP) {
                    PlaybookDefinition.Symptom symptom = findSymptom(playbook, stringVal(ctx.get("symptomId")));
                    if (symptom != null) {
                        blocks.add(stepQuickReplies(symptom, intVal(ctx.get("currentStepIndex"), 0), playbook));
                    }
                }
                yield blocks;
            }
            case OUT_OF_SCOPE -> {
                List<Map<String, Object>> blocks = new ArrayList<>();
                blocks.add(textBlock(
                        "Mình là trợ lý chẩn đoán thiết bị trong hệ thống Hesta nên chỉ hỗ trợ các vấn đề liên quan đến thiết bị nhà thông minh. Bạn hãy chọn một mục bên dưới để tiếp tục nhé.",
                        "info", true));
                if (conv.getState() == MaintenanceChatState.SELECT_SYMPTOM) {
                    blocks.add(symptomQuickReplies(playbook, "OFFLINE".equals(ctx.get("deviceStatus"))));
                } else if (conv.getState() == MaintenanceChatState.GUIDED_STEP) {
                    PlaybookDefinition.Symptom symptom = findSymptom(playbook, stringVal(ctx.get("symptomId")));
                    if (symptom != null) {
                        blocks.add(stepQuickReplies(symptom, intVal(ctx.get("currentStepIndex"), 0), playbook));
                    }
                }
                yield blocks;
            }
            case FALLBACK_QUICK_REPLY -> {
                ctx.put("consecutiveClarifies", 0);
                appendUserNote(ctx, value);
                yield handleFreeTextFallback(conv, ctx, playbook, false);
            }
        };
    }

    private List<Map<String, Object>> handleImageMessage(
            MaintenanceChatConversation conv, UUID userId, UUID homeId, Map<String, Object> ctx,
            PlaybookDefinition playbook, UUID attachmentId, String userNote) {
        if (conv.getState() == MaintenanceChatState.SELECT_DEVICE
                || conv.getState() == MaintenanceChatState.RESOLVED
                || conv.getState() == MaintenanceChatState.TICKET_CREATED) {
            return List.of(textBlock("Vui lòng chọn thiết bị đang kiểm tra trước khi gửi ảnh nhé.", "warn"));
        }

        // Kiểm tra quyền sở hữu attachment (chặn dùng ảnh của user khác / nhà khác)
        MaintenanceAttachmentService.AttachmentContent rawContent =
                maintenanceAttachmentService.loadOwnedForAiAnalysis(userId, homeId, attachmentId);

        addConversationAttachment(ctx, attachmentId);
        if (userNote != null && !userNote.isBlank()) {
            appendUserNote(ctx, userNote);
        }

        if (!rolloutService.isAiImageEnabled(homeId)) {
            return List.of(textBlock(
                    "Đã lưu ảnh để bạn đính kèm vào yêu cầu bảo trì. Tính năng phân tích ảnh AI hiện không bật cho nhà này nên bạn hãy tiếp tục bằng các nút chọn bên dưới.",
                    "info"));
        }

        Object consentObj = ctx.get("imageAiConsent");
        if (consentObj == null) {
            ctx.put("pendingImageAttachmentId", attachmentId.toString());
            if (userNote != null && !userNote.isBlank()) {
                ctx.put("pendingImageNote", userNote);
            } else {
                ctx.remove("pendingImageNote");
            }
            Map<String, Object> privacy = new LinkedHashMap<>();
            privacy.put("type", "privacy_notice");
            privacy.put("attachmentId", attachmentId.toString());
            privacy.put("text", "Lưu ý quyền riêng tư: Vui lòng không chụp lộ khuôn mặt người, giấy tờ tùy thân hoặc màn hình chứa thông tin cá nhân. Ảnh sẽ được làm sạch EXIF/GPS và gửi tới dịch vụ AI để hỗ trợ quan sát bề mặt thiết bị.");
            privacy.put("acceptValue", ACTION_IMAGE_CONSENT_ACCEPT);
            privacy.put("declineValue", ACTION_IMAGE_CONSENT_DECLINE);
            return List.of(privacy);
        }

        if (Boolean.FALSE.equals(consentObj)) {
            return List.of(textBlock(
                    "Đã lưu ảnh để đính kèm vào yêu cầu bảo trì (không gửi tới AI phân tích theo lựa chọn của bạn).",
                    "info"));
        }

        return runImageAnalysis(conv, userId, homeId, ctx, playbook, attachmentId, rawContent.bytes(), userNote);
    }

    private List<Map<String, Object>> runImageAnalysis(
            MaintenanceChatConversation conv, UUID userId, UUID homeId, Map<String, Object> ctx,
            PlaybookDefinition playbook, UUID attachmentId, byte[] rawBytes, String userNote) {
        int imgCount = intVal(ctx.get("imageAnalysisCount"), 0);
        int maxImgConv = aiProperties.getImageAnalyzer().getMaxImagesPerConversation();
        int maxLlmConv = aiProperties.getTextInterpreter().getMaxLlmCallsPerConversation();
        if (imgCount >= maxImgConv || conv.getLlmCallCount() >= maxLlmConv || !rateLimiter.tryConsumeImageAnalysis(userId)) {
            return List.of(textBlock(
                    "Đã lưu ảnh để đính kèm vào yêu cầu bảo trì. Bạn đã dùng hết lượt phân tích ảnh AI cho phiên này/hôm nay, bạn vẫn có thể đính ảnh này cho kỹ thuật viên xem.",
                    "info"));
        }

        Optional<ImageSanitizer.SanitizedImage> sanitizedOpt =
                imageSanitizer.sanitize(rawBytes, aiProperties.getImageAnalyzer().getMaxDimensionPx());
        if (sanitizedOpt.isEmpty()) {
            return List.of(textBlock(
                    "Đã lưu ảnh để đính kèm vào yêu cầu bảo trì. Định dạng ảnh này hiện chưa hỗ trợ phân tích tự động, kỹ thuật viên vẫn sẽ xem được ảnh khi bạn gửi ticket.",
                    "info"));
        }

        ImageSanitizer.SanitizedImage sanitized = sanitizedOpt.get();
        Optional<ImageAnalysisModels.RawImageAnalysis> rawOpt = imageAnalyzer.analyze(
                new MaintenanceImageAnalyzer.AnalyzeInput(
                        conv.getId(),
                        stringVal(ctx.get("deviceType")),
                        stringVal(ctx.get("model")),
                        stringVal(ctx.get("deviceStatus")),
                        stringVal(ctx.get("offlineDurationLabel")),
                        buildBriefHealthAnomaly(ctx),
                        stringVal(ctx.get("symptomId")),
                        playbook,
                        sanitized.bytes(),
                        sanitized.mimeType(),
                        null,
                        userNote
                ));

        if (rawOpt.isEmpty()) {
            recordMetricEvent(conv, "LLM_FALLBACK", Map.of("reason", "IMAGE_ERROR"));
            return List.of(textBlock(
                    "Hiện chưa thể phân tích tự động ảnh này. Ảnh vẫn đã được lưu để bạn đính kèm cho kỹ thuật viên xem khi tạo yêu cầu bảo trì.",
                    "info"));
        }

        ImageAnalysisModels.RawImageAnalysis raw = rawOpt.get();
        conv.setLlmCallCount(conv.getLlmCallCount() + 1);
        conv.setLlmTotalTokens(conv.getLlmTotalTokens() + Math.max(0, raw.totalTokens()));
        ctx.put("imageAnalysisCount", imgCount + 1);
        recordMetricEvent(conv, "IMAGE_ANALYZED", Map.of());

        boolean hasOpenAnomaly = ctx.get("openAnomalySummary") != null && !stringVal(ctx.get("openAnomalySummary")).isBlank();
        int retakeCount = intVal(ctx.get("imageRetakeCount"), 0);
        ImageAnalysisModels.HandledImageDecision decision = imageAnalysisHandler.evaluate(
                raw, playbook, stringVal(ctx.get("deviceStatus")), hasOpenAnomaly, retakeCount);

        if (decision.observations() != null && !decision.observations().isEmpty()
                && decision.decision() != ImageAnalysisModels.DecisionType.PRIVACY_REJECT_PEOPLE_OR_DOCS) {
            appendImageObservations(ctx, decision.observations());
        }

        Map<String, Object> analysisBlock = new LinkedHashMap<>();
        analysisBlock.put("type", "image_analysis");
        analysisBlock.put("attachmentId", attachmentId.toString());
        analysisBlock.put("decision", decision.decision().name());
        analysisBlock.put("imageQuality", decision.imageQuality() != null ? decision.imageQuality().name() : null);
        analysisBlock.put("observations", decision.observations());
        analysisBlock.put("staticAdvice", decision.staticAdvice());
        analysisBlock.put("telemetryMismatch", decision.telemetryMismatch());
        analysisBlock.put("suggestDeleteAttachment", decision.suggestDeleteAttachment());
        analysisBlock.put("aiGenerated", true);
        if (decision.suspectedSymptomId() != null) {
            analysisBlock.put("suspectedSymptomId", decision.suspectedSymptomId());
            analysisBlock.put("suspectedSymptomLabel", decision.suspectedSymptomLabel());
        }

        return switch (decision.decision()) {
            case PRIVACY_REJECT_PEOPLE_OR_DOCS -> {
                recordMetricEvent(conv, "IMAGE_PRIVACY_REJECT", Map.of());
                List<Map<String, Object>> opts = List.of(
                        quickReplyOption(ACTION_REMOVE_IMAGE_PREFIX + attachmentId, "Xoá ảnh này khỏi phiên hỗ trợ", "danger")
                );
                yield List.of(analysisBlock, quickRepliesBlock(opts));
            }
            case HAZARD_URGENT -> {
                recordMetricEvent(conv, "SAFETY_LAYER2", Map.of("kind", "IMAGE_URGENT"));
                ctx.put("safetyFlag", true);
                List<Map<String, Object>> blocks = new ArrayList<>();
                blocks.add(analysisBlock);
                blocks.addAll(enterSafetyEscalation(conv, userId, ctx, playbook));
                yield blocks;
            }
            case HAZARD_CAUTION -> {
                recordMetricEvent(conv, "SAFETY_LAYER2", Map.of("kind", "IMAGE_CAUTION"));
                recordMetricEvent(conv, "CAUTION_PROMPTED", Map.of("kind", "IMAGE"));
                List<String> sigs = decision.hazardSignals().stream().map(Enum::name).toList();
                List<Map<String, Object>> blocks = new ArrayList<>();
                blocks.add(analysisBlock);
                blocks.addAll(askCautionConfirmation(ctx, sigs));
                yield blocks;
            }
            case RETAKE_NEEDED -> {
                recordMetricEvent(conv, "IMAGE_RETAKE", Map.of());
                ctx.put("imageRetakeCount", retakeCount + 1);
                yield List.of(analysisBlock);
            }
            case RETAKE_LIMIT_ESCALATE -> {
                List<Map<String, Object>> opts = List.of(
                        quickReplyOption(ACTION_ESCALATE_NOW, "Tạo yêu cầu bảo trì kèm ảnh", "primary")
                );
                yield List.of(analysisBlock, quickRepliesBlock(opts));
            }
            case CONFIRM_SUSPECTED_SYMPTOM -> {
                ctx.put("imageRetakeCount", 0);
                List<Map<String, Object>> opts = List.of(
                        quickReplyOption(ACTION_CONFIRM_IMAGE_SYMPTOM_PREFIX + decision.suspectedSymptomId(),
                                "Đúng, thiết bị đang gặp tình trạng này", "primary"),
                        quickReplyOption(ACTION_REJECT_IMAGE_SYMPTOM, "Không phải tình trạng này", "default")
                );
                yield List.of(analysisBlock, quickRepliesBlock(opts));
            }
            case TELEMETRY_MISMATCH_OBSERVATIONS, OBSERVATIONS_ONLY, FALLBACK_ATTACH_ONLY -> {
                ctx.put("imageRetakeCount", 0);
                yield List.of(analysisBlock);
            }
        };
    }

    private List<Map<String, Object>> handleQuickReply(
            MaintenanceChatConversation conv, UUID userId, UUID homeId, Map<String, Object> ctx,
            PlaybookDefinition playbook, String value) {
        ctx.put("consecutiveClarifies", 0);

        if (ACTION_IMAGE_CONSENT_ACCEPT.equals(value)) {
            ctx.put("imageAiConsent", true);
            UUID pendingId = parseUuidOrNull(stringVal(ctx.remove("pendingImageAttachmentId")));
            String pendingNote = stringVal(ctx.remove("pendingImageNote"));
            if (pendingId != null) {
                MaintenanceAttachmentService.AttachmentContent rawContent =
                        maintenanceAttachmentService.loadOwnedForAiAnalysis(userId, homeId, pendingId);
                return runImageAnalysis(conv, userId, homeId, ctx, playbook, pendingId, rawContent.bytes(), pendingNote);
            }
            return List.of(textBlock("Cảm ơn bạn đã đồng ý. Bạn có thể gửi ảnh thiết bị để trợ lý hỗ trợ quan sát.", "ok"));
        }

        if (ACTION_IMAGE_CONSENT_DECLINE.equals(value)) {
            ctx.put("imageAiConsent", false);
            ctx.remove("pendingImageAttachmentId");
            ctx.remove("pendingImageNote");
            return List.of(textBlock(
                    "Đã ghi nhận lựa chọn không phân tích ảnh bằng AI. Ảnh của bạn vẫn được giữ lại để đính kèm vào ticket nếu bạn muốn gửi kỹ thuật viên xem.",
                    "info"));
        }

        if (value.startsWith(ACTION_REMOVE_IMAGE_PREFIX)) {
            String targetId = value.substring(ACTION_REMOVE_IMAGE_PREFIX.length());
            removeConversationAttachment(ctx, targetId);
            UUID parsedAttId = parseUuidOrNull(targetId);
            if (parsedAttId != null) {
                try {
                    maintenanceAttachmentService.deleteOwnedUnattached(userId, homeId, parsedAttId);
                } catch (Exception ignored) {
                    // Already removed or attached
                }
            }
            return List.of(textBlock("Đã gỡ và xoá ảnh khỏi hệ thống lưu trữ. Bạn có thể chụp lại ảnh khác chỉ tập trung vào vỏ ngoài thiết bị.", "ok"));
        }

        if (value.startsWith(ACTION_CONFIRM_IMAGE_SYMPTOM_PREFIX)) {
            String targetSymId = value.substring(ACTION_CONFIRM_IMAGE_SYMPTOM_PREFIX.length());
            conv.setState(MaintenanceChatState.SELECT_SYMPTOM);
            return handleQuickReply(conv, userId, homeId, ctx, playbook, targetSymId);
        }

        if (ACTION_REJECT_IMAGE_SYMPTOM.equals(value)) {
            return List.of(
                    textBlock("Đã ghi nhận. Bạn hãy chọn triệu chứng đúng nhất bên dưới nhé:", "info"),
                    symptomQuickReplies(playbook, "OFFLINE".equals(ctx.get("deviceStatus")))
            );
        }

        if (ACTION_RECHECK_STATUS.equals(value) && conv.getDevice() != null) {
            if (!rateLimiter.tryConsumeRecheck(conv.getId())) {
                List<Map<String, Object>> opts = new ArrayList<>();
                opts.add(quickReplyOption(ACTION_RECHECK_STATUS, "Kiểm tra lại trạng thái", "primary"));
                opts.addAll(symptomQuickReplyOptions(playbook, "OFFLINE".equals(ctx.get("deviceStatus"))));
                return List.of(
                        textBlock("Bạn vừa kiểm tra trạng thái cách đây ít giây. Vui lòng đợi khoảng 10 giây rồi bấm kiểm tra lại, hoặc chọn triệu chứng bên dưới nhé.", "info"),
                        quickRepliesBlock(opts)
                );
            }
            DeviceDiagnosticContext refreshed = diagnosticContextService.buildContext(homeId, conv.getDevice().getId(), userId);
            ctx.put("deviceStatus", refreshed.status() != null ? refreshed.status().name() : "UNKNOWN");
            ctx.put("offlineDurationLabel", refreshed.offlineDurationLabel());
            List<Map<String, Object>> blocks = new ArrayList<>();
            blocks.add(deviceCardBlock(refreshed));
            if (refreshed.status() == DeviceStatus.ONLINE) {
                blocks.add(textBlock(
                        "Thiết bị \"" + refreshed.name() + "\" đã trực tuyến trở lại! Nếu bạn vẫn gặp vấn đề khác hãy chọn triệu chứng bên dưới, hoặc đóng phiên hỗ trợ này.",
                        "ok"));
                blocks.add(symptomQuickReplies(playbook, false));
            } else {
                String dur = refreshed.offlineDurationLabel() != null ? " (" + refreshed.offlineDurationLabel() + ")" : "";
                blocks.add(textBlock(
                        "Thiết bị \"" + refreshed.name() + "\" vẫn đang mất kết nối" + dur + ". Bạn hãy chọn triệu chứng bên dưới để bắt đầu các bước kiểm tra an toàn nhé.",
                        "info"));
                blocks.add(symptomQuickReplies(playbook, true));
            }
            return blocks;
        }

        if (ACTION_ESCALATE_NOW.equals(value)) {
            return enterNormalEscalation(conv, userId, ctx,
                    "Mình đã chuẩn bị sẵn thông tin yêu cầu bảo trì. Bạn hãy chọn khung giờ rảnh và bấm xác nhận bên dưới nhé.");
        }

        if (ACTION_CAUTION_YES.equals(value) || ACTION_CAUTION_UNSURE.equals(value)) {
            ctx.remove("pendingCaution");
            recordMetricEvent(conv, "CAUTION_ANSWERED", Map.of("answer", ACTION_CAUTION_YES.equals(value) ? "YES" : "UNSURE"));
            return enterSafetyEscalation(conv, userId, ctx, playbook);
        }

        if (ACTION_CAUTION_NO.equals(value)) {
            ctx.remove("pendingCaution");
            ctx.put("cautionConfirmedNo", true);
            recordMetricEvent(conv, "CAUTION_ANSWERED", Map.of("answer", "NO"));
            List<Map<String, Object>> blocks = new ArrayList<>();
            blocks.add(textBlock(
                    "Đã ghi nhận không có mùi khét/tia lửa/khói. Tuy nhiên để an toàn, bạn nên tạm ngưng sử dụng thiết bị cho đến khi hoàn tất kiểm tra. Hãy chọn bước tiếp theo bên dưới:",
                    "warn"));
            if (conv.getState() == MaintenanceChatState.GUIDED_STEP) {
                PlaybookDefinition.Symptom symptom = findSymptom(playbook, stringVal(ctx.get("symptomId")));
                if (symptom != null) {
                    blocks.addAll(buildStepBlocks(symptom, intVal(ctx.get("currentStepIndex"), 0), playbook));
                    return blocks;
                }
            }
            blocks.add(symptomQuickReplies(playbook, "OFFLINE".equals(ctx.get("deviceStatus"))));
            return blocks;
        }

        if (value.startsWith(ACTION_SWITCH_SYMPTOM_PREFIX)) {
            String targetSymptomId = value.substring(ACTION_SWITCH_SYMPTOM_PREFIX.length());
            conv.setState(MaintenanceChatState.SELECT_SYMPTOM);
            return handleQuickReply(conv, userId, homeId, ctx, playbook, targetSymptomId);
        }

        if (ACTION_KEEP_CURRENT_STEP.equals(value) && conv.getState() == MaintenanceChatState.GUIDED_STEP) {
            PlaybookDefinition.Symptom symptom = findSymptom(playbook, stringVal(ctx.get("symptomId")));
            if (symptom != null) {
                return buildStepBlocks(symptom, intVal(ctx.get("currentStepIndex"), 0), playbook);
            }
        }

        if (conv.getState() == MaintenanceChatState.SELECT_SYMPTOM) {
            PlaybookDefinition.Symptom symptom = findSymptom(playbook, value);
            if (symptom == null) {
                return List.of(
                        textBlock("Vui lòng chọn một trong các triệu chứng gợi ý bên dưới.", "warn"),
                        symptomQuickReplies(playbook, "OFFLINE".equals(ctx.get("deviceStatus")))
                );
            }
            ctx.put("symptomId", symptom.getId());
            ctx.put("symptomLabel", symptom.getLabel());
            ctx.put("symptomDescription", symptom.getTicketDescription());
            ctx.put("stepsTried", new ArrayList<Map<String, Object>>());
            if (!Boolean.TRUE.equals(ctx.get("layer1SafetyFlag"))) {
                ctx.put("safetyFlag", false);
            }

            if (symptom.isHardwareOnly() || symptom.getSteps() == null || symptom.getSteps().isEmpty()) {
                return enterNormalEscalation(conv, userId, ctx,
                        "Triệu chứng \"" + symptom.getLabel() + "\" có dấu hiệu liên quan phần cứng nên không nên tự xử lý. Mình đã chuẩn bị sẵn thông tin yêu cầu bảo trì, bạn hãy chọn khung giờ rảnh để kỹ thuật viên đến kiểm tra nhé.");
            }

            ctx.put("currentStepIndex", 0);
            conv.setState(MaintenanceChatState.GUIDED_STEP);
            return buildStepBlocks(symptom, 0, playbook);
        }

        if (conv.getState() == MaintenanceChatState.GUIDED_STEP) {
            PlaybookDefinition.Symptom symptom = findSymptom(playbook, stringVal(ctx.get("symptomId")));
            if (symptom == null) {
                return enterNormalEscalation(conv, userId, ctx, "Mình chuyển bạn sang bước tạo yêu cầu bảo trì nhé.");
            }
            int idx = intVal(ctx.get("currentStepIndex"), 0);
            List<PlaybookDefinition.Step> steps = symptom.getSteps();

            if (ACTION_STEP_OK.equals(value)) {
                recordStepResult(ctx, steps, idx, "Đã ổn");
                conv.setState(MaintenanceChatState.RESOLVED);
                conv.setStatus(MaintenanceChatStatus.RESOLVED);
                return List.of(textBlock(
                        "Tuyệt vời! Thiết bị \"" + stringVal(ctx.get("deviceName")) + "\" đã ổn định lại. Phiên chẩn đoán đã được đánh dấu hoàn tất và không cần tạo ticket bảo trì.",
                        "ok"));
            }

            if (ACTION_STEP_NOT_OK.equals(value)) {
                recordStepResult(ctx, steps, idx, "Chưa ổn");
                if (idx + 1 < steps.size()) {
                    ctx.put("currentStepIndex", idx + 1);
                    return buildStepBlocks(symptom, idx + 1, playbook);
                }
                return enterNormalEscalation(conv, userId, ctx,
                        "Bạn đã thử toàn bộ các bước kiểm tra an toàn nhưng thiết bị vẫn chưa ổn. Mình đề xuất tạo yêu cầu bảo trì để kỹ thuật viên kiểm tra trực tiếp. Hãy chọn khung giờ rảnh bên dưới và bấm xác nhận nhé.");
            }

            return buildStepBlocks(symptom, idx, playbook);
        }

        // PICK_SLOTS / CONFIRM
        return buildEscalationInteractiveBlocks(conv, userId, ctx,
                "Bạn hãy chọn khung giờ rảnh (1–5 khung trong 14 ngày tới, 07:00–21:00) và bấm nút Xác nhận tạo ticket bên dưới nhé.");
    }

    private List<Map<String, Object>> askCautionConfirmation(Map<String, Object> ctx, List<String> signals) {
        ctx.put("pendingCaution", true);
        String hint = (signals != null && !signals.isEmpty())
                ? " (" + String.join(", ", signals) + ")"
                : "";
        List<Map<String, Object>> opts = List.of(
                quickReplyOption(ACTION_CAUTION_YES, "Có, có dấu hiệu mất an toàn", "danger"),
                quickReplyOption(ACTION_CAUTION_UNSURE, "Không chắc chắn", "danger"),
                quickReplyOption(ACTION_CAUTION_NO, "Không có các dấu hiệu trên", "default")
        );
        return List.of(
                textBlock(
                        "Mình nhận thấy mô tả có thể liên quan đến an toàn điện/nhiệt" + hint
                                + ". Xin xác nhận: thiết bị có mùi khét, nóng bất thường, tia lửa hoặc khói không?",
                        "warn", true),
                quickRepliesBlock(opts)
        );
    }

    private static String sanitizePlainText(String raw, int maxLen) {
        if (raw == null) return "";
        // Loại bỏ ký tự điều khiển trừ \n và \t, giữ văn bản thuần (không render HTML)
        String cleaned = raw.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) : cleaned;
    }

    private List<Map<String, Object>> enterSafetyEscalation(
            MaintenanceChatConversation conv, UUID userId, Map<String, Object> ctx, PlaybookDefinition playbook) {
        ctx.put("safetyFlag", true);
        if (ctx.get("symptomLabel") == null) {
            ctx.put("symptomLabel", playbook.getSafety().getLabel());
            ctx.put("symptomDescription", "Phát hiện dấu hiệu mất an toàn: " + playbook.getSafety().getLabel());
        }
        conv.setState(MaintenanceChatState.PICK_SLOTS);
        conv.setContextJson(ctx);

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(textBlock(playbook.getSafety().getAdvice(), "danger"));
        blocks.addAll(buildEscalationInteractiveBlocks(conv, userId, ctx, null));
        return blocks;
    }

    private List<Map<String, Object>> enterNormalEscalation(
            MaintenanceChatConversation conv, UUID userId, Map<String, Object> ctx, String message) {
        conv.setState(MaintenanceChatState.PICK_SLOTS);
        return buildEscalationInteractiveBlocks(conv, userId, ctx, message);
    }

    private List<Map<String, Object>> buildEscalationInteractiveBlocks(
            MaintenanceChatConversation conv, UUID userId, Map<String, Object> ctx, String introMessage) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (introMessage != null) {
            blocks.add(textBlock(introMessage, "info"));
        }
        Map<String, Object> slotPicker = new LinkedHashMap<>();
        slotPicker.put("type", "slot_picker");
        slotPicker.put("prompt", "Chọn 1–5 khung giờ bạn có thể tiếp kỹ thuật viên (07:00–21:00, trong 14 ngày tới)");
        blocks.add(slotPicker);

        String diagnosisSummary = buildDiagnosisSummary(ctx);
        String aiSummary = stringVal(ctx.get("aiSummary"));
        if (aiSummary == null
                && userId != null
                && ticketSummarizer.isEnabled()
                && rolloutService.isAiTextEnabled(conv.getHome() != null ? conv.getHome().getId() : null)
                && conv.getLlmCallCount() < aiProperties.getTextInterpreter().getMaxLlmCallsPerConversation()
                && rateLimiter.tryConsumeLlmCall(userId)) {
            Optional<MaintenanceTicketSummarizer.SummaryResult> sumOpt = ticketSummarizer.summarize(conv.getId(), diagnosisSummary);
            if (sumOpt.isPresent()) {
                aiSummary = sumOpt.get().summary();
                ctx.put("aiSummary", aiSummary);
                conv.setLlmCallCount(conv.getLlmCallCount() + 1);
                conv.setLlmTotalTokens(conv.getLlmTotalTokens() + Math.max(0, sumOpt.get().totalTokens()));
            }
        }

        boolean safetyFlag = Boolean.TRUE.equals(ctx.get("safetyFlag")) || Boolean.TRUE.equals(ctx.get("layer1SafetyFlag"));
        MaintenancePriority suggestedPriority = resolveTicketPriority(ctx, safetyFlag);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", "ticket_summary");
        summary.put("title", buildTicketTitle(stringVal(ctx.get("deviceName")), safetyFlag));
        summary.put("description", buildTicketDescription(ctx));
        summary.put("priority", suggestedPriority.name());
        summary.put("safetyFlag", safetyFlag);
        summary.put("deviceId", stringVal(ctx.get("deviceId")));
        summary.put("deviceName", stringVal(ctx.get("deviceName")));
        summary.put("roomName", stringVal(ctx.get("roomName")));
        summary.put("diagnosisSummary", diagnosisSummary);
        if (aiSummary != null) {
            summary.put("aiSummary", aiSummary);
        }
        List<String> extracted = listOfStrings(ctx.get("extractedDetails"));
        if (!extracted.isEmpty()) {
            summary.put("extractedDetails", String.join("; ", extracted));
        }
        String imageObs = buildImageObservationsSection(ctx);
        if (imageObs != null) {
            summary.put("imageObservations", imageObs);
        }
        List<String> convAttIds = listOfStrings(ctx.get("conversationAttachmentIds"));
        if (!convAttIds.isEmpty() && conv.getHome() != null) {
            UUID hid = conv.getHome().getId();
            summary.put("suggestedAttachments", convAttIds.stream()
                    .map(id -> Map.of(
                            "attachmentId", id,
                            "url", "/api/v1/homes/" + hid + "/maintenance-attachments/" + id + "/content"))
                    .toList());
        }
        blocks.add(summary);
        return blocks;
    }

    private List<Map<String, Object>> handleFreeTextFallback(
            MaintenanceChatConversation conv, Map<String, Object> ctx, PlaybookDefinition playbook, boolean aiFallbackNotice) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        String msg = aiFallbackNotice
                ? "Mình đã lưu ghi chú của bạn. Hiện trợ lý thông minh tạm bận nên bạn hãy chọn nhanh một mục bên dưới để tiếp tục nhé."
                : "Mình đã lưu ghi chú của bạn để đính kèm vào yêu cầu bảo trì nếu cần. Bạn vui lòng chọn các nút gợi ý bên dưới để tiếp tục nhé.";
        blocks.add(textBlock(msg, "info"));
        if (conv.getState() == MaintenanceChatState.SELECT_SYMPTOM) {
            List<Map<String, Object>> opts = new ArrayList<>(symptomQuickReplyOptions(playbook, "OFFLINE".equals(ctx.get("deviceStatus"))));
            opts.add(quickReplyOption(ACTION_ESCALATE_NOW, "Tạo yêu cầu bảo trì ngay", "primary"));
            blocks.add(quickRepliesBlock(opts));
        } else if (conv.getState() == MaintenanceChatState.GUIDED_STEP) {
            PlaybookDefinition.Symptom symptom = findSymptom(playbook, stringVal(ctx.get("symptomId")));
            if (symptom != null) {
                blocks.addAll(buildStepBlocks(symptom, intVal(ctx.get("currentStepIndex"), 0), playbook));
            }
        } else if (conv.getState() == MaintenanceChatState.PICK_SLOTS || conv.getState() == MaintenanceChatState.CONFIRM) {
            blocks.addAll(buildEscalationInteractiveBlocks(conv, null, ctx, null));
        }
        return blocks;
    }

    private List<Map<String, Object>> buildStepBlocks(
            PlaybookDefinition.Symptom symptom, int idx, PlaybookDefinition playbook) {
        List<PlaybookDefinition.Step> steps = symptom.getSteps();
        if (steps == null || steps.isEmpty() || idx < 0 || idx >= steps.size()) {
            return List.of(
                    textBlock("Hướng dẫn chẩn đoán cho thiết bị này vừa được cập nhật. Bạn vui lòng chọn lại triệu chứng bên dưới để tiếp tục nhé.", "info"),
                    symptomQuickReplies(playbook, false)
            );
        }
        PlaybookDefinition.Step step = steps.get(idx);
        String body = "Bước " + (idx + 1) + "/" + steps.size() + " — " + step.getTitle() + "\n" + step.getInstruction();

        return List.of(
                textBlock(body, "default"),
                stepQuickReplies(symptom, idx, playbook)
        );
    }

    private MaintenancePriority resolveTicketPriority(Map<String, Object> ctx, boolean safetyFlag) {
        if (safetyFlag) {
            return MaintenancePriority.URGENT;
        }
        PlaybookDefinition playbook = playbookService.getForDeviceType(stringVal(ctx.get("deviceType")));
        if (playbook != null && playbook.getMinEscalationPriority() != null && !playbook.getMinEscalationPriority().isBlank()) {
            try {
                MaintenancePriority floor = MaintenancePriority.valueOf(
                        playbook.getMinEscalationPriority().trim().toUpperCase(Locale.ROOT));
                if (floor.ordinal() > MaintenancePriority.NORMAL.ordinal()) {
                    return floor;
                }
            } catch (IllegalArgumentException ignored) {
                // fallback to NORMAL
            }
        }
        return MaintenancePriority.NORMAL;
    }

    private boolean isStaleGuidedStep(
            MaintenanceChatConversation conv, Map<String, Object> ctx, PlaybookDefinition playbook) {
        if (conv.getState() != MaintenanceChatState.GUIDED_STEP) {
            return false;
        }
        PlaybookDefinition.Symptom symptom = findSymptom(playbook, stringVal(ctx.get("symptomId")));
        if (symptom == null || symptom.getSteps() == null || symptom.getSteps().isEmpty()) {
            return true;
        }
        int idx = intVal(ctx.get("currentStepIndex"), 0);
        return idx < 0 || idx >= symptom.getSteps().size();
    }

    private Map<String, Object> stepQuickReplies(
            PlaybookDefinition.Symptom symptom, int idx, PlaybookDefinition playbook) {
        List<PlaybookDefinition.Step> steps = symptom.getSteps();
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(quickReplyOption(ACTION_STEP_OK, "Đã ổn, thiết bị hoạt động lại", "primary"));
        options.add(quickReplyOption(ACTION_STEP_NOT_OK,
                idx + 1 < steps.size() ? "Chưa ổn, xem bước tiếp theo" : "Vẫn chưa ổn, tạo yêu cầu bảo trì",
                "default"));
        options.add(quickReplyOption(ACTION_SAFETY_HAZARD, playbook.getSafety().getLabel(), "danger"));
        return quickRepliesBlock(options);
    }

    private Map<String, Object> symptomQuickReplies(PlaybookDefinition playbook, boolean isOffline) {
        return quickRepliesBlock(symptomQuickReplyOptions(playbook, isOffline));
    }

    private List<Map<String, Object>> symptomQuickReplyOptions(PlaybookDefinition playbook, boolean isOffline) {
        List<PlaybookDefinition.Symptom> ordered = new ArrayList<>(playbook.getSymptoms());
        if (isOffline) {
            ordered.sort(Comparator.comparing((PlaybookDefinition.Symptom s) -> !s.getId().contains("OFFLINE")));
        }
        List<Map<String, Object>> options = new ArrayList<>();
        for (PlaybookDefinition.Symptom s : ordered) {
            options.add(quickReplyOption(s.getId(), s.getLabel(), "default"));
        }
        options.add(quickReplyOption(ACTION_SAFETY_HAZARD, playbook.getSafety().getLabel(), "danger"));
        return options;
    }

    private static PlaybookDefinition.Symptom findSymptom(PlaybookDefinition playbook, String symptomId) {
        if (playbook == null || playbook.getSymptoms() == null || symptomId == null) {
            return null;
        }
        return playbook.getSymptoms().stream()
                .filter(s -> s.getId().equals(symptomId))
                .findFirst()
                .orElse(null);
    }

    // =========================================================================
    // Block & template builders
    // =========================================================================

    private Map<String, Object> deviceCardBlock(DeviceDiagnosticContext diag) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "device_card");
        block.put("deviceId", diag.deviceId().toString());
        block.put("name", diag.name());
        block.put("deviceType", diag.deviceType());
        block.put("model", diag.model());
        block.put("roomName", diag.roomName());
        block.put("status", diag.status() != null ? diag.status().name() : "UNKNOWN");
        block.put("lastSeen", diag.lastSeen() != null ? diag.lastSeen().toString() : null);
        block.put("offlineDurationLabel", diag.offlineDurationLabel());
        block.put("currentStateSummary", diag.currentStateSummary());
        if (diag.health() != null) {
            block.put("healthPercent", diag.health().healthPercent());
            block.put("switchCycles", diag.health().switchCycles());
            block.put("chatteringWarning", diag.health().chatteringWarning());
        }
        List<Map<String, Object>> anomalies = diag.openAnomalies() == null ? List.of() : diag.openAnomalies().stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.id().toString());
                    m.put("type", a.anomalyType());
                    m.put("severity", a.severity());
                    m.put("reason", a.reason());
                    return m;
                }).toList();
        block.put("openAnomalies", anomalies);
        block.put("homeWideOutage", diag.homeWideOutage());
        block.put("homeTotalDevices", diag.homeTotalDevices());
        block.put("homeOfflineDevices", diag.homeOfflineDevices());
        return block;
    }

    private static Map<String, Object> ticketCreatedBlock(
            UUID ticketId, String code, String title, MaintenanceTicketStatus status,
            MaintenancePriority priority, boolean alreadyExisted) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "ticket_created");
        block.put("ticketId", ticketId.toString());
        block.put("code", code);
        block.put("title", title);
        block.put("status", status != null ? status.name() : null);
        block.put("priority", priority != null ? priority.name() : null);
        block.put("alreadyExisted", alreadyExisted);
        return block;
    }

    private static Map<String, Object> textBlock(String text, String tone) {
        return textBlock(text, tone, false);
    }

    private static Map<String, Object> textBlock(String text, String tone, boolean aiGenerated) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        block.put("tone", tone);
        if (aiGenerated) {
            block.put("aiGenerated", true);
        }
        return block;
    }

    private static Map<String, Object> quickRepliesBlock(List<Map<String, Object>> options) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "quick_replies");
        block.put("options", options);
        return block;
    }

    private static Map<String, Object> quickReplyOption(String value, String label, String tone) {
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("value", value);
        opt.put("label", label);
        opt.put("tone", tone);
        return opt;
    }

    String buildDiagnosisSummary(Map<String, Object> ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("- Thiết bị: ").append(nonBlank(stringVal(ctx.get("deviceName")), "Không rõ"));
        String room = stringVal(ctx.get("roomName"));
        if (room != null && !room.isBlank()) {
            sb.append(" (Phòng: ").append(room).append(")");
        }
        String type = stringVal(ctx.get("deviceType"));
        if (type != null && !type.isBlank()) {
            sb.append(" [").append(type).append("]");
        }
        sb.append("\n- Trạng thái lúc chẩn đoán: ").append(nonBlank(stringVal(ctx.get("deviceStatus")), "UNKNOWN"));
        String offlineDur = stringVal(ctx.get("offlineDurationLabel"));
        if (offlineDur != null && !offlineDur.isBlank()) {
            sb.append(" (mất kết nối ").append(offlineDur).append(")");
        }
        String anomaly = stringVal(ctx.get("openAnomalySummary"));
        if (anomaly != null && !anomaly.isBlank()) {
            sb.append("\n- Cảnh báo bất thường: ").append(anomaly);
        }
        if (Boolean.TRUE.equals(ctx.get("safetyFlag"))) {
            sb.append("\n- CỜ AN TOÀN: KHẨN CẤP (người dùng báo dấu hiệu mất an toàn điện/nhiệt)");
        }
        String symptomLabel = stringVal(ctx.get("symptomLabel"));
        if (symptomLabel != null && !symptomLabel.isBlank()) {
            sb.append("\n- Triệu chứng: ").append(symptomLabel);
        }
        List<Map<String, Object>> stepsTried = listOfMaps(ctx.get("stepsTried"));
        if (!stepsTried.isEmpty()) {
            sb.append("\n- Các bước đã thử:");
            for (Map<String, Object> step : stepsTried) {
                sb.append("\n  + ").append(step.get("title")).append(": ").append(step.get("result"));
            }
        }
        List<String> extracted = listOfStrings(ctx.get("extractedDetails"));
        if (!extracted.isEmpty()) {
            sb.append("\n- Chi tiết (do người dùng mô tả): ").append(String.join(" | ", extracted));
        }
        List<String> notes = listOfStrings(ctx.get("userNotes"));
        if (!notes.isEmpty()) {
            sb.append("\n- Ghi chú thêm của khách: ").append(String.join(" | ", notes));
        }
        String editedSummary = stringVal(ctx.get("editedSummary"));
        String aiSummary = stringVal(ctx.get("aiSummary"));
        if (editedSummary != null && !editedSummary.isBlank()) {
            sb.append("\n- Tóm tắt bổ sung (khách xác nhận): ").append(editedSummary);
        } else if (aiSummary != null && !aiSummary.isBlank()) {
            sb.append("\n- AI tóm tắt: ").append(aiSummary);
        }
        return sb.toString();
    }

    private String buildTicketTitle(String deviceName, boolean safetyFlag) {
        String name = nonBlank(deviceName, "thiết bị");
        String prefix = safetyFlag ? "[KHẨN CẤP] Kiểm tra an toàn " : "Kiểm tra ";
        String raw = prefix + name;
        return raw.length() > 150 ? raw.substring(0, 150) : raw;
    }

    private String buildTicketDescription(Map<String, Object> ctx) {
        List<String> parts = new ArrayList<>();
        String symptomDesc = stringVal(ctx.get("symptomDescription"));
        if (symptomDesc != null && !symptomDesc.isBlank()) {
            parts.add(symptomDesc);
        }
        List<String> notes = listOfStrings(ctx.get("userNotes"));
        if (!notes.isEmpty()) {
            parts.add("Ghi chú khách hàng: " + String.join("; ", notes));
        }
        String combined = String.join("\n", parts);
        return combined.isBlank() ? null : (combined.length() > 2000 ? combined.substring(0, 2000) : combined);
    }

    private String buildDeviceIssueNote(Map<String, Object> ctx) {
        String label = stringVal(ctx.get("symptomLabel"));
        List<String> notes = listOfStrings(ctx.get("userNotes"));
        String combined = (label != null ? label : "")
                + (!notes.isEmpty() ? (label != null ? " — " : "") + String.join("; ", notes) : "");
        return combined.isBlank() ? null : (combined.length() > 1000 ? combined.substring(0, 1000) : combined);
    }

    private String buildManualFallbackNote(Map<String, Object> ctx) {
        return buildDeviceIssueNote(ctx);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void backfillDeviceContextIfMissing(MaintenanceChatConversation conv, Map<String, Object> ctx) {
        if (conv == null || conv.getDevice() == null) {
            return;
        }
        Device d = conv.getDevice();
        if (stringVal(ctx.get("deviceId")) == null && d.getId() != null) {
            ctx.put("deviceId", d.getId().toString());
        }
        if (stringVal(ctx.get("deviceName")) == null || stringVal(ctx.get("deviceName")).isBlank()) {
            String fallbackName = d.getName() != null && !d.getName().isBlank() ? d.getName() : d.getDeviceType();
            if (fallbackName != null) {
                ctx.put("deviceName", fallbackName);
            }
        }
        if (stringVal(ctx.get("deviceType")) == null || stringVal(ctx.get("deviceType")).isBlank()) {
            if (d.getDeviceType() != null) {
                ctx.put("deviceType", d.getDeviceType());
            }
        }
        if (stringVal(ctx.get("model")) == null && d.getModel() != null) {
            ctx.put("model", d.getModel());
        }
        if (stringVal(ctx.get("roomName")) == null && d.getRoom() != null && d.getRoom().getName() != null) {
            ctx.put("roomName", d.getRoom().getName());
        }
        if (stringVal(ctx.get("deviceStatus")) == null && d.getStatus() != null) {
            ctx.put("deviceStatus", d.getStatus().name());
        }
    }

    private void recordMetricEvent(MaintenanceChatConversation conv, String eventType, Map<String, Object> metadata) {
        if (eventRepository == null || conv == null || conv.getHome() == null) {
            return;
        }
        try {
            eventRepository.save(MaintenanceChatEvent.builder()
                    .conversationId(conv.getId())
                    .homeId(conv.getHome().getId())
                    .eventType(eventType)
                    .metadata(metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>())
                    .build());
        } catch (Exception e) {
            log.debug("Failed to record metric event {}: {}", eventType, e.getMessage());
        }
    }

    private void saveMessage(MaintenanceChatConversation conv, MaintenanceChatRole role, List<Map<String, Object>> blocks) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("blocks", blocks);
        messageRepository.save(MaintenanceChatMessage.builder()
                .conversation(conv)
                .role(role)
                .contentJson(content)
                .build());
    }

    private MaintenanceAssistantDtos.ConversationResponse toConversationResponse(
            MaintenanceChatConversation conv, List<MaintenanceChatMessage> messages) {
        Map<String, Object> ctx = ensureContext(conv);
        List<MaintenanceAssistantDtos.MessageItem> items = new ArrayList<>(messages.stream()
                .map(m -> new MaintenanceAssistantDtos.MessageItem(
                        m.getId(),
                        m.getRole(),
                        extractBlocks(m.getContentJson()),
                        m.getCreatedAt()))
                .toList());
        MaintenanceChatState effectiveState = conv.getState();
        if (conv.getStatus() == MaintenanceChatStatus.ACTIVE && conv.getState() == MaintenanceChatState.GUIDED_STEP) {
            PlaybookDefinition playbook = playbookService.getForDeviceType(stringVal(ctx.get("deviceType")));
            if (isStaleGuidedStep(conv, ctx, playbook)) {
                effectiveState = MaintenanceChatState.SELECT_SYMPTOM;
                items.add(new MaintenanceAssistantDtos.MessageItem(
                        null,
                        MaintenanceChatRole.ASSISTANT,
                        List.of(
                                textBlock("Hướng dẫn chẩn đoán cho thiết bị này vừa được cập nhật. Bạn vui lòng chọn lại triệu chứng bên dưới để tiếp tục nhé.", "info"),
                                symptomQuickReplies(playbook, "OFFLINE".equals(ctx.get("deviceStatus")))
                        ),
                        conv.getUpdatedAt()));
            }
        }
        MaintenanceAssistantRolloutService.HomeCapabilities caps =
                rolloutService.resolveForHome(conv.getHome() != null ? conv.getHome().getId() : null);
        return new MaintenanceAssistantDtos.ConversationResponse(
                conv.getId(),
                conv.getHome().getId(),
                conv.getDevice() != null ? conv.getDevice().getId() : null,
                effectiveState,
                conv.getStatus(),
                conv.getTicket() != null ? conv.getTicket().getId() : null,
                buildManualFallbackNote(ctx),
                caps.aiTextEnabled(),
                caps.aiImageEnabled(),
                conv.getUserRating() != null ? conv.getUserRating().intValue() : null,
                conv.getUserFeedbackNote(),
                items,
                conv.getCreatedAt(),
                conv.getUpdatedAt()
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractBlocks(Map<String, Object> contentJson) {
        if (contentJson == null) {
            return List.of();
        }
        Object blocks = contentJson.get("blocks");
        if (blocks instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .toList();
        }
        return List.of();
    }

    private String buildImageObservationsSection(Map<String, Object> ctx) {
        List<String> obs = listOfStrings(ctx.get("imageObservationsList"));
        if (obs.isEmpty()) {
            return null;
        }
        String joined = obs.stream().map(o -> "- " + o).collect(Collectors.joining("\n"));
        String section = "AI quan sát từ ảnh, chưa kiểm chứng:\n" + joined;
        return section.length() > 2000 ? section.substring(0, 2000) : section;
    }

    private void addConversationAttachment(Map<String, Object> ctx, UUID attachmentId) {
        List<String> ids = new ArrayList<>(listOfStrings(ctx.get("conversationAttachmentIds")));
        String idStr = attachmentId.toString();
        if (!ids.contains(idStr) && ids.size() < 5) {
            ids.add(idStr);
            ctx.put("conversationAttachmentIds", ids);
        }
    }

    private void removeConversationAttachment(Map<String, Object> ctx, String idStr) {
        List<String> ids = new ArrayList<>(listOfStrings(ctx.get("conversationAttachmentIds")));
        ids.remove(idStr);
        ctx.put("conversationAttachmentIds", ids);
    }

    private void appendImageObservations(Map<String, Object> ctx, List<String> observations) {
        List<String> existing = new ArrayList<>(listOfStrings(ctx.get("imageObservationsList")));
        for (String o : observations) {
            if (o != null && !o.isBlank() && !existing.contains(o) && existing.size() < 12) {
                existing.add(o);
            }
        }
        ctx.put("imageObservationsList", existing);
    }

    private String resolveUserDisplayText(
            MaintenanceChatConversation conv, MaintenanceAssistantDtos.MessageInputType type, String value, Map<String, Object> ctx) {
        if (type == MaintenanceAssistantDtos.MessageInputType.IMAGE) {
            return "[Đã gửi ảnh thiết bị]";
        }
        if (type == MaintenanceAssistantDtos.MessageInputType.TEXT) {
            return value;
        }
        if (ACTION_IMAGE_CONSENT_ACCEPT.equals(value)) {
            return "Đồng ý gửi ảnh để AI phân tích";
        }
        if (ACTION_IMAGE_CONSENT_DECLINE.equals(value)) {
            return "Không phân tích AI, chỉ đính kèm ảnh";
        }
        if (ACTION_CAUTION_YES.equals(value)) {
            return "Có, có dấu hiệu mất an toàn";
        }
        if (ACTION_CAUTION_UNSURE.equals(value)) {
            return "Không chắc chắn";
        }
        if (ACTION_CAUTION_NO.equals(value)) {
            return "Không có các dấu hiệu mất an toàn";
        }
        if (value.startsWith(ACTION_REMOVE_IMAGE_PREFIX)) {
            return "Xoá ảnh khỏi phiên hỗ trợ";
        }
        if (value.startsWith(ACTION_CONFIRM_IMAGE_SYMPTOM_PREFIX)) {
            return "Đúng, thiết bị đang gặp tình trạng này";
        }
        if (ACTION_REJECT_IMAGE_SYMPTOM.equals(value)) {
            return "Không phải tình trạng này";
        }
        if (ACTION_RECHECK_STATUS.equals(value)) {
            return "Kiểm tra lại trạng thái";
        }
        if (ACTION_STEP_OK.equals(value)) {
            return "Đã ổn, thiết bị hoạt động lại";
        }
        if (ACTION_STEP_NOT_OK.equals(value)) {
            return "Chưa ổn";
        }
        if (ACTION_SAFETY_HAZARD.equals(value)) {
            return "Có mùi khét, tia lửa, nóng bất thường, nước vào hoặc rò điện";
        }
        if (conv.getState() == MaintenanceChatState.SELECT_DEVICE) {
            UUID id = parseUuidOrNull(value);
            if (id != null) {
                return deviceRepository.findById(id).map(Device::getName).orElse(value);
            }
        }
        PlaybookDefinition playbook = playbookService.getForDeviceType(stringVal(ctx.get("deviceType")));
        return playbook.getSymptoms().stream()
                .filter(s -> s.getId().equals(value))
                .map(PlaybookDefinition.Symptom::getLabel)
                .findFirst()
                .orElse(value);
    }

    private void recordStepResult(Map<String, Object> ctx, List<PlaybookDefinition.Step> steps, int idx, String result) {
        if (idx < 0 || idx >= steps.size()) {
            return;
        }
        List<Map<String, Object>> tried = new ArrayList<>(listOfMaps(ctx.get("stepsTried")));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stepId", steps.get(idx).getId());
        entry.put("title", steps.get(idx).getTitle());
        entry.put("result", result);
        tried.add(entry);
        ctx.put("stepsTried", tried);
    }

    private void appendUserNote(Map<String, Object> ctx, String note) {
        List<String> notes = new ArrayList<>(listOfStrings(ctx.get("userNotes")));
        notes.add(note);
        ctx.put("userNotes", notes);
    }

    private void appendExtractedDetail(Map<String, Object> ctx, String detail) {
        List<String> details = new ArrayList<>(listOfStrings(ctx.get("extractedDetails")));
        if (!details.contains(detail)) {
            details.add(detail);
            ctx.put("extractedDetails", details);
        }
    }

    private static String extractLastAssistantText(List<MaintenanceChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            MaintenanceChatMessage msg = history.get(i);
            if (msg.getRole() == MaintenanceChatRole.ASSISTANT) {
                for (Map<String, Object> b : extractBlocks(msg.getContentJson())) {
                    if ("text".equals(b.get("type")) && b.get("text") != null) {
                        return b.get("text").toString();
                    }
                }
            }
        }
        return null;
    }

    private static List<String> extractRecentTurns(List<MaintenanceChatMessage> history) {
        List<String> turns = new ArrayList<>();
        for (MaintenanceChatMessage msg : history) {
            for (Map<String, Object> b : extractBlocks(msg.getContentJson())) {
                if ("text".equals(b.get("type")) && b.get("text") != null) {
                    turns.add(msg.getRole() + ": " + b.get("text"));
                    break;
                }
            }
        }
        return turns;
    }

    private static String buildBriefHealthAnomaly(Map<String, Object> ctx) {
        String anomaly = stringVal(ctx.get("openAnomalySummary"));
        return anomaly != null ? anomaly : "";
    }

    private MaintenanceChatConversation requireOwnedConversation(UUID conversationId, UUID homeId, UUID userId) {
        return conversationRepository.findByIdAndHomeIdAndUserId(conversationId, homeId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.MAINTENANCE_CHAT_NOT_FOUND));
    }

    private User requireUser(UUID userId) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return userRepository.findById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private static Map<String, Object> ensureContext(MaintenanceChatConversation conv) {
        if (conv.getContextJson() == null) {
            conv.setContextJson(new LinkedHashMap<>());
        }
        return new LinkedHashMap<>(conv.getContextJson());
    }

    private static UUID parseUuidOrThrow(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.MAINTENANCE_INVALID_REQUEST);
        }
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stringVal(Object v) {
        return v == null ? null : v.toString();
    }

    private static int intVal(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    private static String nonBlank(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList();
        }
        return List.of();
    }

    private static List<String> listOfStrings(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }
}
