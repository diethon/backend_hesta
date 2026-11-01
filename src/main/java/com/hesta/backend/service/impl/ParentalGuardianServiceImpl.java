package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.dto.request.CreateParentalGuardianRequest;
import com.hesta.backend.dto.request.GuardianSimulateEventRequest;
import com.hesta.backend.dto.request.UpdateParentalGuardianRequest;
import com.hesta.backend.dto.response.ParentalGuardianLogResponse;
import com.hesta.backend.dto.response.ParentalGuardianResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.ParentalGuardianLog;
import com.hesta.backend.entity.ParentalGuardianRule;
import com.hesta.backend.enums.GuardianStatus;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.ParentalGuardianLogRepository;
import com.hesta.backend.repository.ParentalGuardianRuleRepository;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.NotificationService;
import com.hesta.backend.service.ParentalGuardianService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParentalGuardianServiceImpl implements ParentalGuardianService {

    private final ParentalGuardianRuleRepository ruleRepository;
    private final ParentalGuardianLogRepository logRepository;
    private final DeviceRepository deviceRepository;
    private final HomeAuthorizationService authorization;
    private final DeviceCommandService deviceCommandService;
    private final NotificationService notificationService;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    @Transactional
    public ParentalGuardianResponse createRule(UUID userId, UUID homeId, CreateParentalGuardianRequest request) {
        Home home = authorization.requireSceneManagement(userId, homeId);

        Device targetDevice = deviceRepository.findById(request.getTargetDeviceId())
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        validateDeviceInHome(targetDevice, homeId);

        Device cameraDevice = null;
        if (request.getCameraDeviceId() != null) {
            cameraDevice = deviceRepository.findById(request.getCameraDeviceId())
                    .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
            validateDeviceInHome(cameraDevice, homeId);
        }

        ParentalGuardianRule rule = ParentalGuardianRule.builder()
                .home(home)
                .targetDevice(targetDevice)
                .cameraDevice(cameraDevice)
                .name(request.getName().trim())
                .enabled(true)
                .parentAway(false)
                .allowedDurationMinutes(request.getAllowedDurationMinutes())
                .warningMinutesBefore(request.getWarningMinutesBefore())
                .status(GuardianStatus.IDLE)
                .autoLockout(request.isAutoLockout())
                .build();

        ParentalGuardianRule saved = ruleRepository.save(rule);
        recordLog(saved, "CREATED", "Khởi tạo quy tắc Giám hộ con trẻ cho thiết bị " + targetDevice.getName());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParentalGuardianResponse> getRules(UUID userId, UUID homeId) {
        authorization.requireAccess(userId, homeId);
        return ruleRepository.findAllByHomeIdOrderByCreatedAtDesc(homeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ParentalGuardianResponse getRule(UUID userId, UUID homeId, UUID ruleId) {
        authorization.requireAccess(userId, homeId);
        ParentalGuardianRule rule = findRule(homeId, ruleId);
        return toResponse(rule);
    }

    @Override
    @Transactional
    public ParentalGuardianResponse updateRule(UUID userId, UUID homeId, UUID ruleId, UpdateParentalGuardianRequest request) {
        authorization.requireSceneManagement(userId, homeId);
        ParentalGuardianRule rule = findRule(homeId, ruleId);

        Device targetDevice = deviceRepository.findById(request.getTargetDeviceId())
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        validateDeviceInHome(targetDevice, homeId);

        Device cameraDevice = null;
        if (request.getCameraDeviceId() != null) {
            cameraDevice = deviceRepository.findById(request.getCameraDeviceId())
                    .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
            validateDeviceInHome(cameraDevice, homeId);
        }

        rule.setName(request.getName().trim());
        rule.setTargetDevice(targetDevice);
        rule.setCameraDevice(cameraDevice);
        rule.setAllowedDurationMinutes(request.getAllowedDurationMinutes());
        rule.setWarningMinutesBefore(request.getWarningMinutesBefore());
        rule.setAutoLockout(request.isAutoLockout());
        rule.setEnabled(request.isEnabled());

        ParentalGuardianRule saved = ruleRepository.save(rule);
        recordLog(saved, "UPDATED", "Cập nhật cấu hình quy tắc Giám hộ: hạn mức " + saved.getAllowedDurationMinutes() + " phút.");
        return toResponse(saved);
    }

    @Override
    @Transactional
    public ParentalGuardianResponse setEnabled(UUID userId, UUID homeId, UUID ruleId, boolean enabled) {
        authorization.requireSceneManagement(userId, homeId);
        ParentalGuardianRule rule = findRule(homeId, ruleId);
        rule.setEnabled(enabled);
        if (!enabled && rule.getStatus() == GuardianStatus.LOCKED_OUT) {
            rule.setStatus(GuardianStatus.IDLE);
            rule.setSessionStartedAt(null);
            rule.setSessionExpiresAt(null);
        }
        ParentalGuardianRule saved = ruleRepository.save(rule);
        recordLog(saved, enabled ? "ENABLED" : "DISABLED",
                enabled ? "Đã bật quy tắc Giám hộ con trẻ." : "Đã tắt quy tắc Giám hộ con trẻ.");
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteRule(UUID userId, UUID homeId, UUID ruleId) {
        authorization.requireSceneManagement(userId, homeId);
        ParentalGuardianRule rule = findRule(homeId, ruleId);
        ruleRepository.delete(rule);
    }

    @Override
    @Transactional
    public ParentalGuardianResponse setParentAway(UUID userId, UUID homeId, UUID ruleId, boolean parentAway) {
        authorization.requireSceneManagement(userId, homeId);
        ParentalGuardianRule rule = findRule(homeId, ruleId);
        rule.setParentAway(parentAway);

        if (!parentAway) {
            // Phụ huynh về nhà -> reset trạng thái khóa để con dùng bình thường
            if (rule.getStatus() == GuardianStatus.LOCKED_OUT) {
                rule.setStatus(GuardianStatus.IDLE);
                rule.setSessionStartedAt(null);
                rule.setSessionExpiresAt(null);
            }
            recordLog(rule, "DISARMED", "Phụ huynh đã về nhà. Chế độ Giám hộ con trẻ tạm thời tắt kích hoạt.");
            notifyParents(rule, "Chế độ Giám hộ: Phụ huynh về nhà",
                    "Đã tắt chế độ giới hạn màn hình cho " + rule.getTargetDevice().getName() + ".",
                    NotificationPriority.LOW);
        } else {
            recordLog(rule, "ARMED", "Phụ huynh đã rời nhà. Chế độ Giám hộ con trẻ đã được kích hoạt sẵn sàng.");
            notifyParents(rule, "Chế độ Giám hộ: Đã kích hoạt khi vắng nhà",
                    "Thiết bị " + rule.getTargetDevice().getName() + " sẽ bị giới hạn " + rule.getAllowedDurationMinutes() + " phút nếu được bật.",
                    NotificationPriority.MEDIUM);
        }

        return toResponse(ruleRepository.save(rule));
    }

    @Override
    @Transactional
    public ParentalGuardianResponse grantExtension(UUID userId, UUID homeId, UUID ruleId, int extraMinutes) {
        authorization.requireSceneManagement(userId, homeId);
        ParentalGuardianRule rule = findRule(homeId, ruleId);
        int added = Math.max(1, extraMinutes);
        OffsetDateTime now = OffsetDateTime.now();

        if (rule.getStatus() == GuardianStatus.LOCKED_OUT || rule.getStatus() == GuardianStatus.IDLE) {
            rule.setStatus(GuardianStatus.COUNTDOWN);
            rule.setSessionStartedAt(now);
            rule.setSessionExpiresAt(now.plusMinutes(added));
            // Mở lại nguồn thiết bị
            try {
                deviceCommandService.sendCommand(rule.getTargetDevice().getId(), "TURN_ON", Map.of(), StateChangeSource.MANUAL);
            } catch (Exception ex) {
                log.warn("Could not power on device on extension: {}", ex.getMessage());
            }
        } else {
            OffsetDateTime currentExpires = rule.getSessionExpiresAt() != null ? rule.getSessionExpiresAt() : now;
            rule.setSessionExpiresAt(currentExpires.plusMinutes(added));
            rule.setStatus(GuardianStatus.COUNTDOWN);
        }

        recordLog(rule, "EXTENSION_GRANTED", "Phụ huynh đã duyệt cấp thêm " + added + " phút sử dụng.");
        notifyParents(rule, "Giám hộ con trẻ: Đã gia hạn thời gian",
                "Đã cộng thêm " + added + " phút cho thiết bị " + rule.getTargetDevice().getName() + ".",
                NotificationPriority.MEDIUM);

        return toResponse(ruleRepository.save(rule));
    }

    @Override
    @Transactional
    public ParentalGuardianResponse unlock(UUID userId, UUID homeId, UUID ruleId) {
        authorization.requireSceneManagement(userId, homeId);
        ParentalGuardianRule rule = findRule(homeId, ruleId);
        rule.setStatus(GuardianStatus.IDLE);
        rule.setSessionStartedAt(null);
        rule.setSessionExpiresAt(null);

        recordLog(rule, "UNLOCKED", "Phụ huynh đã mở khóa cưỡng chế thiết bị " + rule.getTargetDevice().getName());
        return toResponse(ruleRepository.save(rule));
    }

    @Override
    @Transactional
    public ParentalGuardianResponse simulate(UUID userId, UUID homeId, UUID ruleId, GuardianSimulateEventRequest request) {
        authorization.requireSceneManagement(userId, homeId);
        ParentalGuardianRule rule = findRule(homeId, ruleId);
        String event = request.getEventType() != null ? request.getEventType().trim().toUpperCase() : "";

        OffsetDateTime now = OffsetDateTime.now();
        switch (event) {
            case "PARENT_DEPARTURE" -> {
                rule.setParentAway(true);
                recordLog(rule, "SIMULATE_DEPARTURE", "[Mô phỏng] Camera AI nhận diện người lớn rời khỏi nhà.");
                notifyParents(rule, "[Mô phỏng] Phụ huynh rời nhà",
                        "Chế độ Giám hộ con trẻ đã tự động kích hoạt bảo vệ " + rule.getTargetDevice().getName(),
                        NotificationPriority.MEDIUM);
            }
            case "PARENT_ARRIVAL" -> {
                rule.setParentAway(false);
                rule.setStatus(GuardianStatus.IDLE);
                rule.setSessionStartedAt(null);
                rule.setSessionExpiresAt(null);
                recordLog(rule, "SIMULATE_ARRIVAL", "[Mô phỏng] Camera AI nhận diện phụ huynh đã về nhà.");
                notifyParents(rule, "[Mô phỏng] Phụ huynh về nhà",
                        "Đã tạm dừng chế độ giám hộ cho " + rule.getTargetDevice().getName(),
                        NotificationPriority.LOW);
            }
            case "DEVICE_POWER_ON" -> {
                rule.setParentAway(true);
                rule.setStatus(GuardianStatus.COUNTDOWN);
                rule.setSessionStartedAt(now);
                rule.setSessionExpiresAt(now.plusMinutes(rule.getAllowedDurationMinutes()));
                recordLog(rule, "SIMULATE_POWER_ON", "[Mô phỏng] Bé bật " + rule.getTargetDevice().getName() + ". Bắt đầu đếm ngược " + rule.getAllowedDurationMinutes() + " phút.");
                notifyParents(rule, "[Mô phỏng] Bé bắt đầu sử dụng TV",
                        "Bé bật TV lúc " + now.format(TIME_FMT) + ". Thời gian cho phép là " + rule.getAllowedDurationMinutes() + " phút.",
                        NotificationPriority.MEDIUM);
            }
            case "DEVICE_POWER_OFF" -> {
                recordLog(rule, "SIMULATE_POWER_OFF", "[Mô phỏng] Thiết bị đã tắt.");
            }
            case "FAST_FORWARD_EXPIRE" -> {
                // Giả lập hết giờ ngay lập tức
                rule.setStatus(GuardianStatus.LOCKED_OUT);
                rule.setSessionExpiresAt(now.minusSeconds(1));
                try {
                    deviceCommandService.sendCommand(rule.getTargetDevice().getId(), "TURN_OFF", Map.of(), StateChangeSource.AUTOMATION);
                } catch (Exception ex) {
                    log.warn("Could not dispatch TURN_OFF in simulation: {}", ex.getMessage());
                }
                recordLog(rule, "LOCKOUT_ENFORCED", "[Mô phỏng] Hết giờ xem (" + rule.getAllowedDurationMinutes() + " phút). Đã tự động cắt nguồn " + rule.getTargetDevice().getName() + " và kích hoạt khóa cưỡng chế!");
                notifyParents(rule, "Giám hộ: Đã hết giờ xem " + rule.getTargetDevice().getName(),
                        "Hệ thống đã tự động cắt nguồn điện thiết bị an toàn và kích hoạt khóa trẻ em.",
                        NotificationPriority.HIGH);
            }
            case "TAMPER_POWER_ON" -> {
                // Giả lập bé cố tình bấm bật lại khi đang khóa
                rule.setStatus(GuardianStatus.LOCKED_OUT);
                try {
                    deviceCommandService.sendCommand(rule.getTargetDevice().getId(), "TURN_OFF", Map.of(), StateChangeSource.AUTOMATION);
                } catch (Exception ex) {
                    log.warn("Could not dispatch TURN_OFF in tamper simulation: {}", ex.getMessage());
                }
                recordLog(rule, "TAMPER_BLOCKED", "[Mô phỏng] Bé cố tình bấm bật lại ổ cắm/TV khi đang bị khóa. Hệ thống phát hiện và CƯỠNG CHẾ NGẮT NGUỒN NGAY LẬP TỨC!");
                notifyParents(rule, "Cảnh báo Giám hộ: Phát hiện cố tình bật lại TV",
                        "Bé cố bật lại " + rule.getTargetDevice().getName() + " nhưng hệ thống HESTA đã cưỡng chế ngắt nguồn ngay lập tức.",
                        NotificationPriority.HIGH);
            }
            default -> throw new AppException(ErrorCode.GUARDIAN_RULE_INVALID);
        }

        return toResponse(ruleRepository.save(rule));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ParentalGuardianLogResponse> getLogs(UUID userId, UUID homeId, UUID ruleId) {
        authorization.requireAccess(userId, homeId);
        findRule(homeId, ruleId);
        return logRepository.findAllByRuleIdOrderByRecordedAtDesc(ruleId).stream()
                .map(this::toLogResponse)
                .toList();
    }

    @Override
    @Transactional
    public void onDeviceStateChanged(UUID homeId, UUID deviceId, Map<String, Object> currentState) {
        if (homeId == null || deviceId == null) return;
        boolean powerOn = isPowerOn(currentState);

        List<ParentalGuardianRule> rules = ruleRepository.findAllByTargetDeviceIdAndEnabledTrue(deviceId);
        for (ParentalGuardianRule rule : rules) {
            if (!homeId.equals(rule.getHome().getId()) || !rule.isEnabled()) continue;
            if (!rule.isParentAway()) continue; // Phụ huynh ở nhà, không can thiệp

            OffsetDateTime now = OffsetDateTime.now();

            if (powerOn) {
                if (rule.getStatus() == GuardianStatus.LOCKED_OUT) {
                    // CỐ TÌNH BẬT LẠI KHI ĐANG BỊ KHÓA -> CƯỠNG CHẾ NGẮT NGAY
                    if (rule.isAutoLockout()) {
                        log.info("[PARENTAL GUARDIAN] Tamper attempt detected for device {} in home {}. Re-enforcing cutoff!",
                                deviceId, homeId);
                        try {
                            deviceCommandService.sendCommand(deviceId, "TURN_OFF", Map.of(), StateChangeSource.AUTOMATION);
                        } catch (Exception ex) {
                            log.error("Failed to enforce cutoff: {}", ex.getMessage());
                        }
                        recordLog(rule, "TAMPER_BLOCKED",
                                "Phát hiện cố bật " + rule.getTargetDevice().getName() + " khi đang trong thời gian khóa. Hệ thống đã cưỡng chế ngắt nguồn ngay lập tức!");
                        notifyParents(rule, "Cảnh báo Giám hộ: Phát hiện cố bật TV",
                                "Bé cố tình bật " + rule.getTargetDevice().getName() + " khi đang bị khóa. Hệ thống đã tự động ngắt nguồn lập tức.",
                                NotificationPriority.HIGH);
                    }
                } else if (rule.getStatus() == GuardianStatus.IDLE) {
                    // BẮT ĐẦU PHIÊN XEM
                    rule.setStatus(GuardianStatus.COUNTDOWN);
                    rule.setSessionStartedAt(now);
                    rule.setSessionExpiresAt(now.plusMinutes(rule.getAllowedDurationMinutes()));
                    ruleRepository.save(rule);

                    recordLog(rule, "SESSION_STARTED",
                            "Bé bắt đầu bật " + rule.getTargetDevice().getName() + ". Đồng hồ đếm ngược " + rule.getAllowedDurationMinutes() + " phút bắt đầu.");
                    notifyParents(rule, "Giám hộ con trẻ: " + rule.getTargetDevice().getName() + " đã bật",
                            "Bé bắt đầu sử dụng lúc " + now.format(TIME_FMT) + ". Hạn mức thời gian: " + rule.getAllowedDurationMinutes() + " phút.",
                            NotificationPriority.MEDIUM);
                }
            } else {
                if (rule.getStatus() == GuardianStatus.COUNTDOWN || rule.getStatus() == GuardianStatus.WARNING) {
                    recordLog(rule, "DEVICE_OFF", "Thiết bị " + rule.getTargetDevice().getName() + " đã tắt trong phiên sử dụng.");
                }
            }
        }
    }

    @org.springframework.transaction.event.TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onDeviceStateChangedEvent(com.hesta.backend.dto.command.DeviceStateChangedEvent event) {
        if (event == null || event.payload() == null || event.payload().deviceId() == null) return;
        Map<String, Object> state = new HashMap<>();
        if (event.payload().currentState() != null && event.payload().currentState().isObject()) {
            event.payload().currentState().fields().forEachRemaining(entry -> state.put(entry.getKey(), entry.getValue().asText()));
        }
        onDeviceStateChanged(event.homeId(), event.payload().deviceId(), state);
    }

    @Override
    @Transactional
    public void onCameraEvent(UUID homeId, UUID cameraDeviceId, String eventType, Map<String, Object> data) {
        if (homeId == null || cameraDeviceId == null || eventType == null) return;
        List<ParentalGuardianRule> rules = ruleRepository.findAllByCameraDeviceIdAndEnabledTrue(cameraDeviceId);
        String norm = eventType.trim().toUpperCase();

        for (ParentalGuardianRule rule : rules) {
            if (!homeId.equals(rule.getHome().getId()) || !rule.isEnabled()) continue;

            if (norm.contains("DEPARTURE") || norm.contains("PARENT_LEFT") || norm.contains("AWAY")) {
                rule.setParentAway(true);
                ruleRepository.save(rule);
                recordLog(rule, "CAMERA_TRIGGER", "Camera AI phát hiện người lớn rời nhà. Kích hoạt chế độ Giám hộ con trẻ.");
                notifyParents(rule, "Camera AI: Người lớn rời nhà",
                        "Chế độ Giám hộ con trẻ đã được kích hoạt bảo vệ " + rule.getTargetDevice().getName(),
                        NotificationPriority.MEDIUM);
            } else if (norm.contains("ARRIVAL") || norm.contains("PARENT_RETURNED") || norm.contains("HOME")) {
                rule.setParentAway(false);
                if (rule.getStatus() == GuardianStatus.LOCKED_OUT) {
                    rule.setStatus(GuardianStatus.IDLE);
                    rule.setSessionStartedAt(null);
                    rule.setSessionExpiresAt(null);
                }
                ruleRepository.save(rule);
                recordLog(rule, "CAMERA_TRIGGER", "Camera AI phát hiện phụ huynh đã về nhà. Tạm dừng chế độ Giám hộ.");
                notifyParents(rule, "Camera AI: Phụ huynh đã về nhà",
                        "Chế độ Giám hộ cho " + rule.getTargetDevice().getName() + " đã tạm dừng.",
                        NotificationPriority.LOW);
            }
        }
    }

    @Override
    @Transactional
    public void checkDueSessions() {
        OffsetDateTime now = OffsetDateTime.now();
        List<ParentalGuardianRule> activeRules = ruleRepository.findAllByEnabledTrueAndStatusIn(
                List.of(GuardianStatus.COUNTDOWN, GuardianStatus.WARNING));

        for (ParentalGuardianRule rule : activeRules) {
            if (rule.getSessionExpiresAt() == null) continue;

            if (now.isAfter(rule.getSessionExpiresAt())) {
                // HẾT GIỜ -> NGẮT NGUỒN VÀ KHÓA
                rule.setStatus(GuardianStatus.LOCKED_OUT);
                ruleRepository.save(rule);

                try {
                    deviceCommandService.sendCommand(rule.getTargetDevice().getId(), "TURN_OFF", Map.of(), StateChangeSource.AUTOMATION);
                } catch (Exception ex) {
                    log.error("Failed to turn off device {} after guardian session expired: {}",
                            rule.getTargetDevice().getId(), ex.getMessage());
                }

                recordLog(rule, "LOCKOUT_ENFORCED",
                        "Đã hết thời gian cho phép (" + rule.getAllowedDurationMinutes() + " phút). Hệ thống đã tự động ngắt nguồn "
                                + rule.getTargetDevice().getName() + " và kích hoạt khóa cưỡng chế.");
                notifyParents(rule, "Giám hộ: Đã hết giờ xem " + rule.getTargetDevice().getName(),
                        "Thiết bị đã được tự động ngắt nguồn an toàn. Đã kích hoạt khóa trẻ em.",
                        NotificationPriority.HIGH);

            } else {
                long remainingMinutes = Duration.between(now, rule.getSessionExpiresAt()).toMinutes();
                if (remainingMinutes <= rule.getWarningMinutesBefore() && rule.getStatus() == GuardianStatus.COUNTDOWN) {
                    // CHUYỂN SANG CẢNH BÁO
                    rule.setStatus(GuardianStatus.WARNING);
                    ruleRepository.save(rule);

                    recordLog(rule, "WARNING_ISSUED",
                            "Cảnh báo: Còn " + (remainingMinutes + 1) + " phút trước khi thiết bị " + rule.getTargetDevice().getName() + " tự động tắt.");
                    notifyParents(rule, "Nhắc nhở: Sắp hết giờ dùng " + rule.getTargetDevice().getName(),
                            "Còn khoảng " + (remainingMinutes + 1) + " phút trước khi thiết bị tự động ngắt nguồn.",
                            NotificationPriority.LOW);
                }
            }
        }
    }

    private boolean isPowerOn(Map<String, Object> state) {
        if (state == null || state.isEmpty()) return false;
        Object p = state.get("power");
        if (p == null) p = state.get("state");
        if (p == null) p = state.get("status");
        if (p == null) return false;
        if (p instanceof Boolean b) return b;
        String str = String.valueOf(p).trim().toUpperCase();
        return "ON".equals(str) || "TRUE".equals(str) || "1".equals(str) || "ACTIVE".equals(str);
    }

    private void validateDeviceInHome(Device device, UUID homeId) {
        if (device.getRoom() == null || device.getRoom().getHome() == null
                || !homeId.equals(device.getRoom().getHome().getId())) {
            throw new AppException(ErrorCode.AUTOMATION_DEVICE_HOME_MISMATCH);
        }
    }

    private ParentalGuardianRule findRule(UUID homeId, UUID ruleId) {
        return ruleRepository.findByIdAndHomeId(ruleId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.GUARDIAN_RULE_NOT_FOUND));
    }

    private void recordLog(ParentalGuardianRule rule, String eventType, String message) {
        try {
            logRepository.save(ParentalGuardianLog.builder()
                    .rule(rule)
                    .home(rule.getHome())
                    .eventType(eventType)
                    .message(message)
                    .build());
        } catch (Exception ex) {
            log.warn("Could not save parental guardian log: {}", ex.getMessage());
        }
    }

    private void notifyParents(ParentalGuardianRule rule, String title, String message, NotificationPriority priority) {
        try {
            UUID ownerId = rule.getHome().getCreatedBy() != null ? rule.getHome().getCreatedBy().getId() : null;
            if (ownerId != null) {
                notificationService.receiveEvent(new NotificationEvent(
                        ownerId,
                        rule.getHome().getId(),
                        NotificationType.SECURITY,
                        title,
                        message,
                        priority
                ));
            }
        } catch (Exception ex) {
            log.warn("Could not send parental guardian notification: {}", ex.getMessage());
        }
    }

    private ParentalGuardianResponse toResponse(ParentalGuardianRule rule) {
        long remaining = 0;
        if ((rule.getStatus() == GuardianStatus.COUNTDOWN || rule.getStatus() == GuardianStatus.WARNING)
                && rule.getSessionExpiresAt() != null) {
            long diff = Duration.between(OffsetDateTime.now(), rule.getSessionExpiresAt()).toSeconds();
            remaining = Math.max(0, diff);
        }

        return ParentalGuardianResponse.builder()
                .id(rule.getId())
                .homeId(rule.getHome().getId())
                .targetDeviceId(rule.getTargetDevice().getId())
                .targetDeviceName(rule.getTargetDevice().getName())
                .targetDeviceType(rule.getTargetDevice().getDeviceType() != null ? rule.getTargetDevice().getDeviceType() : "TV")
                .cameraDeviceId(rule.getCameraDevice() != null ? rule.getCameraDevice().getId() : null)
                .cameraDeviceName(rule.getCameraDevice() != null ? rule.getCameraDevice().getName() : null)
                .name(rule.getName())
                .enabled(rule.isEnabled())
                .parentAway(rule.isParentAway())
                .allowedDurationMinutes(rule.getAllowedDurationMinutes())
                .warningMinutesBefore(rule.getWarningMinutesBefore())
                .status(rule.getStatus())
                .sessionStartedAt(rule.getSessionStartedAt())
                .sessionExpiresAt(rule.getSessionExpiresAt())
                .remainingSeconds(remaining)
                .autoLockout(rule.isAutoLockout())
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .build();
    }

    private ParentalGuardianLogResponse toLogResponse(ParentalGuardianLog logItem) {
        return ParentalGuardianLogResponse.builder()
                .id(logItem.getId())
                .ruleId(logItem.getRule().getId())
                .homeId(logItem.getHome().getId())
                .eventType(logItem.getEventType())
                .message(logItem.getMessage())
                .recordedAt(logItem.getRecordedAt())
                .build();
    }
}
