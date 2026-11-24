package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.dto.response.SecurityEventResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.entity.SecurityEvent;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.enums.NotificationPriority;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.enums.SecurityEventStatus;
import com.hesta.backend.enums.SecurityEventType;
import com.hesta.backend.enums.SeverityLevel;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.SecurityEventRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import com.hesta.backend.service.NotificationService;
import com.hesta.backend.service.SecurityEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityEventServiceImpl implements SecurityEventService {

    static final Duration MOTION_COOLDOWN = Duration.ofMinutes(5);

    private final SecurityEventRepository securityEventRepository;
    private final HomeMemberRepository homeMemberRepository;
    private final HomeAuthorizationService homeAuthorizationService;
    private final NotificationService notificationService;

    @Override
    @Transactional(readOnly = true)
    public Page<SecurityEventResponse> list(UUID userId, UUID homeId, SecurityEventStatus status,
                                            SecurityEventType eventType, Pageable pageable) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext context = homeAuthorizationService.loadAccessContext(homeId, userId);
        if (context.isOwner() || context.scope() == AccessScope.ALL) {
            return securityEventRepository.findByHomeIdWithFilters(homeId, status, eventType, pageable)
                    .map(SecurityEventResponse::from);
        }
        // Restricted members only see events from sensors they are allowed to view.
        Set<UUID> visibleDeviceIds = homeAuthorizationService.getVisibleDeviceIds(homeId, userId, AccessLevel.VIEW);
        if (visibleDeviceIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return securityEventRepository
                .findByHomeIdAndDeviceIdsWithFilters(homeId, visibleDeviceIds, status, eventType, pageable)
                .map(SecurityEventResponse::from);
    }

    @Override
    @Transactional
    public SecurityEventResponse updateStatus(UUID userId, UUID homeId, UUID eventId, SecurityEventStatus target) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        homeAuthorizationService.requireOwner(userId, homeId);

        SecurityEvent event = securityEventRepository.findByIdAndHomeId(eventId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.SECURITY_EVENT_NOT_FOUND));
        if (event.getStatus() == target) {
            return SecurityEventResponse.from(event);
        }
        if (target == SecurityEventStatus.DETECTED || event.getStatus() != SecurityEventStatus.DETECTED) {
            throw new AppException(ErrorCode.SECURITY_EVENT_INVALID_STATUS);
        }
        event.setStatus(target);
        return SecurityEventResponse.from(securityEventRepository.save(event));
    }

    @Override
    @Transactional
    public void recordMotion(Device device) {
        Home home = device == null ? null : device.getHome();
        if (home == null) {
            return;
        }
        OffsetDateTime cooldownStart = OffsetDateTime.now().minus(MOTION_COOLDOWN);
        if (securityEventRepository.existsByDeviceIdAndDetectedAtAfter(device.getId(), cooldownStart)) {
            return;
        }
        String location = device.getRoom() != null && device.getRoom().getName() != null
                ? device.getRoom().getName().trim()
                : "Khu vực trong nhà";
        SecurityEvent saved = securityEventRepository.save(SecurityEvent.builder()
                .home(home)
                .device(device)
                .eventType(SecurityEventType.MOTION)
                .location(location)
                .severity(SeverityLevel.MEDIUM)
                .build());
        log.info("Security event {} recorded: MOTION at {} (device {})", saved.getId(), location, device.getId());
        notifyMembers(home.getId(), device, location);
    }

    private void notifyMembers(UUID homeId, Device device, String location) {
        String deviceName = device.getName() != null ? device.getName().trim() : "Cảm biến chuyển động";
        for (HomeMember member : homeMemberRepository.findByHomeIdWithUser(homeId)) {
            if (member.getStatus() != MemberStatus.ACTIVE || member.getUser() == null) {
                continue;
            }
            UUID memberId = member.getUser().getId();
            boolean canView = member.getRole() == HomeRole.OWNER
                    || member.getAccessScope() == AccessScope.ALL
                    || homeAuthorizationService.hasDeviceAccess(memberId, device.getId(), AccessLevel.VIEW);
            if (!canView) {
                continue;
            }
            try {
                notificationService.create(new NotificationEvent(
                        memberId,
                        homeId,
                        NotificationType.SECURITY,
                        "Phát hiện chuyển động",
                        deviceName + " phát hiện chuyển động tại " + location + ".",
                        NotificationPriority.MEDIUM
                ));
            } catch (Exception ex) {
                // One failed recipient must not block the others.
                log.warn("Failed to notify member {} about motion in home {}: {}", memberId, homeId, ex.getMessage());
            }
        }
    }
}
