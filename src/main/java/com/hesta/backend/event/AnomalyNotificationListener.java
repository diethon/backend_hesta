package com.hesta.backend.event;

import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.dto.event.AnomalyRaisedEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.enums.*;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import com.hesta.backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyNotificationListener {

    private final HomeMemberRepository homeMemberRepository;
    private final HomeAuthorizationService homeAuthorizationService;
    private final NotificationService notificationService;
    private final DeviceRepository deviceRepository;

    @Async("anomalyNotificationExecutor")
    @EventListener
    @Transactional
    public void onAnomalyRaised(AnomalyRaisedEvent event) {
        if (event == null || event.homeId() == null || event.deviceId() == null) {
            return;
        }

        try {
            Device device = deviceRepository.findByIdWithNode(event.deviceId())
                    .or(() -> deviceRepository.findById(event.deviceId()))
                    .orElse(null);
            List<HomeMember> members = homeMemberRepository.findByHomeIdWithUser(event.homeId());
            if (members == null || members.isEmpty()) {
                log.info("No members found for home {}. Anomaly notification skipped.", event.homeId());
                return;
            }

            String roomName = (device != null && device.getRoom() != null && device.getRoom().getName() != null)
                    ? device.getRoom().getName().trim()
                    : "Khu vực trong nhà";
            String devName = (device != null && device.getName() != null && !device.getName().isBlank())
                    ? device.getName().trim()
                    : (event.deviceName() != null ? event.deviceName() : "Cảm biến an toàn");

            String title = buildTitle(event.anomalyType(), event.severity(), event.isEscalated());
            String message = buildMessage(event, devName, roomName);
            NotificationPriority priority = mapPriority(event.severity());

            for (HomeMember member : members) {
                if (member.getStatus() != MemberStatus.ACTIVE || member.getUser() == null) {
                    continue;
                }

                UUID userId = member.getUser().getId();
                try {
                    // Check if member has at least VIEW permission on this device without N+1 overhead
                    boolean canView = false;
                    if (member.getRole() == HomeRole.OWNER || member.getAccessScope() == AccessScope.ALL) {
                        canView = true;
                    } else if (device != null) {
                        MemberAccessContext ctx = homeAuthorizationService.loadAccessContext(event.homeId(), userId);
                        AccessLevel level = homeAuthorizationService.effectiveLevel(ctx, device);
                        canView = (level != null && level != AccessLevel.NONE);
                    }

                    if (!canView) {
                        log.debug("Member {} lacks VIEW access to device {}. Skipping anomaly notification.", userId, event.deviceId());
                        continue;
                    }

                    NotificationEvent notifEvent = new NotificationEvent(
                            userId,
                            event.homeId(),
                            NotificationType.ANOMALY,
                            title,
                            message,
                            priority,
                            event.anomalyId()
                    );
                    notificationService.create(notifEvent);
                    log.info("Sent anomaly notification for anomaly [{}] to user [{}]", event.anomalyId(), userId);

                } catch (Exception ex) {
                    // Try-catch per recipient: error for one recipient does NOT block the others
                    log.error("Failed to deliver anomaly notification to member {} in home {}: {}",
                            userId, event.homeId(), ex.getMessage());
                }
            }

        } catch (Exception ex) {
            log.error("Failed to process AnomalyRaisedEvent for anomaly {}: {}", event.anomalyId(), ex.getMessage(), ex);
        }
    }

    private String buildTitle(AnomalyType type, SeverityLevel severity, boolean isEscalated) {
        String escalationPrefix = isEscalated ? "CỰC KỲ NGUY HIỂM: " : "";
        if (type == null) return escalationPrefix + "Cảnh báo bất thường thiết bị!";
        return switch (type) {
            case GAS_LEAKAGE -> escalationPrefix + "Cảnh báo rò rỉ khí Gas!";
            case SMOKE_DETECTED -> escalationPrefix + "Cảnh báo phát hiện khói!";
            case HIGH_TEMPERATURE -> escalationPrefix + (severity == SeverityLevel.CRITICAL ? "Cảnh báo nhiệt độ cực hạn!" : "Cảnh báo nhiệt độ cao!");
            case SENSOR_OFFLINE -> "Cảnh báo cảm biến mất kết nối!";
        };
    }

    private String buildMessage(AnomalyRaisedEvent event, String devName, String roomName) {
        if (event.anomalyType() == AnomalyType.SENSOR_OFFLINE) {
            return String.format("Cảm biến an toàn [%s] tại [%s] đã ngừng gửi tín hiệu trong hơn 5 phút. Vui lòng kiểm tra nguồn điện và kết nối.",
                    devName, roomName);
        }

        String curVal = event.currentValue() != null ? event.currentValue() : "vượt ngưỡng";
        String expRange = event.expectedRange() != null ? event.expectedRange() : "ngưỡng an toàn";

        if (event.isEscalated()) {
            return String.format("MỨC ĐỘ NGUY HIỂM TĂNG CAO tại [%s]! Cảm biến [%s] ghi nhận chỉ số %s (ngưỡng bình thường %s). Cần xử lý khẩn cấp ngay lập tức!",
                    roomName, devName, curVal, expRange);
        }

        return String.format("Phát hiện bất thường tại [%s]: Cảm biến [%s] ghi nhận giá trị %s (vượt mức an toàn %s).",
                roomName, devName, curVal, expRange);
    }

    private NotificationPriority mapPriority(SeverityLevel severity) {
        if (severity == null) return NotificationPriority.MEDIUM;
        return switch (severity) {
            case CRITICAL, HIGH -> NotificationPriority.HIGH;
            case MEDIUM -> NotificationPriority.MEDIUM;
            case LOW -> NotificationPriority.LOW;
        };
    }
}
