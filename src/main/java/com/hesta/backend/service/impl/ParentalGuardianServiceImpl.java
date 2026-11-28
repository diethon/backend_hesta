package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.dto.request.CreateParentalGuardianRequest;
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
        List<Device> targetDevices = resolveTargetDevices(request.getTargetDeviceIds(), homeId);
        Device cameraDevice = resolveCamera(request.getCameraDeviceId(), homeId);

        ParentalGuardianRule rule = ParentalGuardianRule.builder()
                .home(home)
                .targetDevices(new ArrayList<>(targetDevices))
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
        recordLog(saved, "CREATED", "Khởi tạo quy tắc Giám hộ con trẻ cho " + deviceNames(saved));
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
        List<Device> targetDevices = resolveTargetDevices(request.getTargetDeviceIds(), homeId);

        rule.setName(request.getName().trim());
        rule.getTargetDevices().clear();
        rule.getTargetDevices().addAll(targetDevices);
        rule.setCameraDevice(resolveCamera(request.getCameraDeviceId(), homeId));
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
        if (!enabled) endSession(rule);
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
            // Phụ huynh về nhà -> dừng đếm giờ và mở khóa để con dùng bình thường
            endSession(rule);
            recordLog(rule, "DISARMED", "Phụ huynh đã về nhà. Chế độ Giám hộ con trẻ tạm thời tắt kích hoạt.");
            notifyParents(rule, "Chế độ Giám hộ: Phụ huynh về nhà",
                    "Đã tắt chế độ giới hạn màn hình cho " + deviceNames(rule) + ".",
                    NotificationPriority.LOW);
        } else {
            recordLog(rule, "ARMED", "Phụ huynh đã rời nhà. Chế độ Giám hộ con trẻ đã được kích hoạt sẵn sàng.");
            notifyParents(rule, "Chế độ Giám hộ: Đã kích hoạt khi vắng nhà",
                    deviceNames(rule) + " sẽ bị giới hạn " + rule.getAllowedDurationMinutes() + " phút nếu được bật.",
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
            // Only lift the lock; with several devices we cannot know which one the child wants back on.
            rule.setStatus(GuardianStatus.COUNTDOWN);
            rule.setSessionStartedAt(now);
            rule.setSessionExpiresAt(now.plusMinutes(added));
        } else {
            OffsetDateTime currentExpires = rule.getSessionExpiresAt() != null ? rule.getSessionExpiresAt() : now;
            rule.setSessionExpiresAt(currentExpires.plusMinutes(added));
            rule.setStatus(GuardianStatus.COUNTDOWN);
        }

        recordLog(rule, "EXTENSION_GRANTED", "Phụ huynh đã duyệt cấp thêm " + added + " phút sử dụng.");
        notifyParents(rule, "Giám hộ con trẻ: Đã gia hạn thời gian",
                "Đã cộng thêm " + added + " phút cho " + deviceNames(rule) + ".",
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

        recordLog(rule, "UNLOCKED", "Phụ huynh đã mở khóa cưỡng chế " + deviceNames(rule));
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

        List<ParentalGuardianRule> rules = ruleRepository.findAllByTargetDevicesIdAndEnabledTrue(deviceId);
        for (ParentalGuardianRule rule : rules) {
            if (!homeId.equals(rule.getHome().getId()) || !rule.isEnabled()) continue;
            if (!rule.isParentAway()) continue; // Phụ huynh ở nhà, không can thiệp
            String deviceName = rule.getTargetDevices().stream()
                    .filter(d -> deviceId.equals(d.getId()))
                    .map(Device::getName)
                    .findFirst()
                    .orElse("thiết bị");

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
                                "Phát hiện cố bật " + deviceName + " khi đang trong thời gian khóa. Hệ thống đã cưỡng chế ngắt nguồn ngay lập tức!");
                        notifyParents(rule, "Cảnh báo Giám hộ: Phát hiện cố bật " + deviceName,
                                "Bé cố tình bật " + deviceName + " khi đang bị khóa. Hệ thống đã tự động ngắt nguồn lập tức.",
                                NotificationPriority.HIGH);
                    }
                } else if (rule.getStatus() == GuardianStatus.IDLE) {
                    // BẮT ĐẦU PHIÊN XEM
                    rule.setStatus(GuardianStatus.COUNTDOWN);
                    rule.setSessionStartedAt(now);
                    rule.setSessionExpiresAt(now.plusMinutes(rule.getAllowedDurationMinutes()));
                    ruleRepository.save(rule);

                    recordLog(rule, "SESSION_STARTED",
                            "Bé bắt đầu bật " + deviceName + ". Đồng hồ đếm ngược " + rule.getAllowedDurationMinutes() + " phút bắt đầu.");
                    notifyParents(rule, "Giám hộ con trẻ: " + deviceName + " đã bật",
                            "Bé bắt đầu sử dụng lúc " + now.format(TIME_FMT) + ". Hạn mức thời gian: " + rule.getAllowedDurationMinutes() + " phút.",
                            NotificationPriority.MEDIUM);
                }
            } else {
                if (rule.getStatus() == GuardianStatus.COUNTDOWN || rule.getStatus() == GuardianStatus.WARNING) {
                    recordLog(rule, "DEVICE_OFF", "Thiết bị " + deviceName + " đã tắt trong phiên sử dụng.");
                }
            }
        }
    }

    // AFTER_COMMIT runs after the publisher's transaction ended: without a new one, saves here would never be committed.
    // Async: see AsyncConfig#deviceStateEventExecutor (running here held two DB connections per command).
    @org.springframework.scheduling.annotation.Async("deviceStateEventExecutor")
    @org.springframework.transaction.event.TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
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
                        "Chế độ Giám hộ con trẻ đã được kích hoạt bảo vệ " + deviceNames(rule),
                        NotificationPriority.MEDIUM);
            } else if (norm.contains("ARRIVAL") || norm.contains("PARENT_RETURNED") || norm.contains("HOME")) {
                rule.setParentAway(false);
                endSession(rule);
                ruleRepository.save(rule);
                recordLog(rule, "CAMERA_TRIGGER", "Camera AI phát hiện phụ huynh đã về nhà. Tạm dừng chế độ Giám hộ.");
                notifyParents(rule, "Camera AI: Phụ huynh đã về nhà",
                        "Chế độ Giám hộ cho " + deviceNames(rule) + " đã tạm dừng.",
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

                for (Device device : rule.getTargetDevices()) {
                    try {
                        deviceCommandService.sendCommand(device.getId(), "TURN_OFF", Map.of(), StateChangeSource.AUTOMATION);
                    } catch (Exception ex) {
                        log.error("Failed to turn off device {} after guardian session expired: {}",
                                device.getId(), ex.getMessage());
                    }
                }

                recordLog(rule, "LOCKOUT_ENFORCED",
                        "Đã hết thời gian cho phép (" + rule.getAllowedDurationMinutes() + " phút). Hệ thống đã tự động ngắt nguồn "
                                + deviceNames(rule) + " và kích hoạt khóa cưỡng chế.");
                notifyParents(rule, "Giám hộ: Đã hết giờ dùng " + deviceNames(rule),
                        "Các thiết bị đã được tự động ngắt nguồn an toàn. Đã kích hoạt khóa trẻ em.",
                        NotificationPriority.HIGH);

            } else {
                long remainingMinutes = Duration.between(now, rule.getSessionExpiresAt()).toMinutes();
                if (remainingMinutes <= rule.getWarningMinutesBefore() && rule.getStatus() == GuardianStatus.COUNTDOWN) {
                    // CHUYỂN SANG CẢNH BÁO
                    rule.setStatus(GuardianStatus.WARNING);
                    ruleRepository.save(rule);

                    recordLog(rule, "WARNING_ISSUED",
                            "Cảnh báo: Còn " + (remainingMinutes + 1) + " phút trước khi " + deviceNames(rule) + " tự động tắt.");
                    notifyParents(rule, "Nhắc nhở: Sắp hết giờ dùng " + deviceNames(rule),
                            "Còn khoảng " + (remainingMinutes + 1) + " phút trước khi thiết bị bị ngắt nguồn.",
                            NotificationPriority.LOW);
                }
            }
        }
    }

    /** Stops any countdown or lockout so the device can be used freely again. */
    private void endSession(ParentalGuardianRule rule) {
        rule.setStatus(GuardianStatus.IDLE);
        rule.setSessionStartedAt(null);
        rule.setSessionExpiresAt(null);
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

    private List<Device> resolveTargetDevices(List<UUID> deviceIds, UUID homeId) {
        List<UUID> uniqueIds = deviceIds.stream().distinct().toList();
        List<Device> devices = deviceRepository.findAllById(uniqueIds);
        if (devices.size() != uniqueIds.size()) {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }
        devices.forEach(device -> validateDeviceInHome(device, homeId));
        return devices;
    }

    private Device resolveCamera(UUID cameraDeviceId, UUID homeId) {
        if (cameraDeviceId == null) return null;
        Device camera = deviceRepository.findById(cameraDeviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        validateDeviceInHome(camera, homeId);
        return camera;
    }

    private String deviceNames(ParentalGuardianRule rule) {
        List<String> names = rule.getTargetDevices().stream().map(Device::getName).toList();
        return names.isEmpty() ? "thiết bị" : String.join(", ", names);
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
                .targetDevices(rule.getTargetDevices().stream()
                        .map(d -> new ParentalGuardianResponse.TargetDevice(d.getId(), d.getName(), d.getDeviceType()))
                        .toList())
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
