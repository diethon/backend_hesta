package com.hesta.backend.service.impl;

import com.hesta.backend.entity.BehaviorEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.BehaviorEventRepository;
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

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OffsetDateTime activate(Device device, UUID userId, String reason) {
        OffsetDateTime now = OffsetDateTime.now();
        var user = users.findById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        events.save(BehaviorEvent.builder().home(device.getRoom().getHome()).device(device).room(device.getRoom())
                .user(user).eventType("MANUAL_OVERRIDE").action(reason).eventSource("MANUAL")
                .currentState(Map.of("expiresAt", now.plus(OVERRIDE_DURATION).toString()))
                .occurredAt(now).build());
        return now.plus(OVERRIDE_DURATION);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID deviceId) {
        return events.findTopByDeviceIdAndEventTypeOrderByOccurredAtDesc(deviceId, "MANUAL_OVERRIDE")
                .map(event -> event.getOccurredAt().plus(OVERRIDE_DURATION).isAfter(OffsetDateTime.now()))
                .orElse(false);
    }
}
