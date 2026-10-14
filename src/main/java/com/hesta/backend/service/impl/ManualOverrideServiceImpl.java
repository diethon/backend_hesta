package com.hesta.backend.service.impl;

import com.hesta.backend.entity.BehaviorEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.BehaviorEventRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.ManualOverrideService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ManualOverrideServiceImpl implements ManualOverrideService {
    private static final Duration OVERRIDE_DURATION = Duration.ofMinutes(30);
    private final BehaviorEventRepository events;
    private final UserRepository users;
    private final DeviceRepository devices;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OffsetDateTime activate(Device device, UUID userId, String reason) {
        OffsetDateTime now = OffsetDateTime.now();
        var user = users.findById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        events.save(BehaviorEvent.builder().home(device.getRoom() != null ? device.getRoom().getHome() : null).device(device).room(device.getRoom())
                .user(user).eventType("MANUAL_OVERRIDE").action(reason).eventSource("MANUAL")
                .currentState(Map.of("expiresAt", now.plus(OVERRIDE_DURATION).toString()))
                .occurredAt(now).build());
        return now.plus(OVERRIDE_DURATION);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OffsetDateTime recordOverride(UUID deviceId, UUID userId, int minutes) {
        Device device = devices.findById(deviceId).orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusMinutes(minutes);
        var user = users.findById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        events.save(BehaviorEvent.builder()
                .home(device.getRoom() != null ? device.getRoom().getHome() : null)
                .device(device)
                .room(device.getRoom())
                .user(user)
                .eventType("MANUAL_OVERRIDE")
                .action("OVERRIDE")
                .eventSource("MANUAL")
                .currentState(Map.of("expiresAt", expiresAt.toString()))
                .occurredAt(now)
                .build());
        return expiresAt;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OffsetDateTime extendOverride(UUID deviceId, UUID userId, int minutes) {
        Device device = devices.findById(deviceId).orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime currentUntil = getOverrideUntil(deviceId);
        OffsetDateTime baseTime = (currentUntil != null && currentUntil.isAfter(now)) ? currentUntil : now;
        OffsetDateTime newExpiresAt = baseTime.plusMinutes(minutes);
        var user = users.findById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        events.save(BehaviorEvent.builder()
                .home(device.getRoom() != null ? device.getRoom().getHome() : null)
                .device(device)
                .room(device.getRoom())
                .user(user)
                .eventType("MANUAL_OVERRIDE")
                .action("EXTEND")
                .eventSource("MANUAL")
                .currentState(Map.of("expiresAt", newExpiresAt.toString()))
                .occurredAt(now)
                .build());
        return newExpiresAt;
    }

    private OffsetDateTime calculateExpiry(BehaviorEvent event) {
        if (event.getCurrentState() != null && event.getCurrentState().containsKey("expiresAt")) {
            try {
                return OffsetDateTime.parse(event.getCurrentState().get("expiresAt").toString());
            } catch (Exception ignored) {}
        }
        return event.getOccurredAt().plus(OVERRIDE_DURATION);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID deviceId) {
        return events.findTopByDeviceIdAndEventTypeOrderByOccurredAtDesc(deviceId, "MANUAL_OVERRIDE")
                .map(event -> !"RESUME".equalsIgnoreCase(event.getAction())
                        && calculateExpiry(event).isAfter(OffsetDateTime.now()))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public OffsetDateTime getOverrideUntil(UUID deviceId) {
        return events.findTopByDeviceIdAndEventTypeOrderByOccurredAtDesc(deviceId, "MANUAL_OVERRIDE")
                .filter(event -> !"RESUME".equalsIgnoreCase(event.getAction()))
                .map(event -> {
                    OffsetDateTime expiry = calculateExpiry(event);
                    return expiry.isAfter(OffsetDateTime.now()) ? expiry : null;
                })
                .orElse(null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resumeAutomation(Device device, UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        var user = users.findById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        events.save(BehaviorEvent.builder().home(device.getRoom() != null ? device.getRoom().getHome() : null).device(device).room(device.getRoom())
                .user(user).eventType("MANUAL_OVERRIDE").action("RESUME").eventSource("MANUAL")
                .currentState(Map.of("resumedAt", now.toString()))
                .occurredAt(now).build());
    }
}
