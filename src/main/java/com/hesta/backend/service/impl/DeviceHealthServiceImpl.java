package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.DeviceHealthResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.service.DeviceHealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceHealthServiceImpl implements DeviceHealthService {

    public static final int MAX_RATED_CYCLES = 100_000;
    private static final int CHATTERING_WINDOW_SECONDS = 60;
    private static final int CHATTERING_THRESHOLD = 10;

    private final DeviceRepository deviceRepository;
    private final DeviceStateHistoryRepository historyRepository;

    private final ConcurrentHashMap<UUID, LinkedList<OffsetDateTime>> switchWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicInteger> cycleCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> chatteringWarnings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> safetyTrippedFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, OffsetDateTime> lastCycleTimes = new ConcurrentHashMap<>();

    @Override
    public void recordSwitch(UUID deviceId) {
        OffsetDateTime now = OffsetDateTime.now();
        lastCycleTimes.put(deviceId, now);
        cycleCounters.computeIfAbsent(deviceId, k -> new AtomicInteger(0)).incrementAndGet();

        switchWindows.compute(deviceId, (id, window) -> {
            if (window == null) {
                window = new LinkedList<>();
            }
            window.addLast(now);
            OffsetDateTime cutoff = now.minusSeconds(CHATTERING_WINDOW_SECONDS);
            while (!window.isEmpty() && window.getFirst().isBefore(cutoff)) {
                window.removeFirst();
            }
            if (window.size() > CHATTERING_THRESHOLD) {
                chatteringWarnings.put(deviceId, true);
                safetyTrippedFlags.put(deviceId, true);
                log.warn("CRITICAL: Relay chattering detected on device {}! {} switches in {}s. Safety tripped.",
                        deviceId, window.size(), CHATTERING_WINDOW_SECONDS);
            }
            return window;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceHealthResponse getDeviceHealth(UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        int dbCycles = 0;
        OffsetDateTime lastDbChange = null;
        List<DeviceStateHistory> histories = historyRepository.findByDeviceIdOrderByChangedAtDesc(deviceId);
        if (histories != null && !histories.isEmpty()) {
            dbCycles = histories.size();
            lastDbChange = histories.get(0).getChangedAt();
        }

        AtomicInteger memoryCounter = cycleCounters.get(deviceId);
        int totalCycles = Math.max(dbCycles, memoryCounter != null ? memoryCounter.get() : 0);

        OffsetDateTime lastCycleAt = lastCycleTimes.get(deviceId);
        if (lastCycleAt == null) {
            lastCycleAt = lastDbChange;
        }

        boolean chattering = chatteringWarnings.getOrDefault(deviceId, false);
        boolean safetyTripped = safetyTrippedFlags.getOrDefault(deviceId, false);

        double healthPercent = Math.max(0.0, Math.round((1.0 - ((double) totalCycles / MAX_RATED_CYCLES)) * 1000.0) / 10.0);

        return DeviceHealthResponse.builder()
                .switchCycles(totalCycles)
                .maxRatedCycles(MAX_RATED_CYCLES)
                .healthPercent(healthPercent)
                .chatteringWarning(chattering)
                .safetyTripped(safetyTripped)
                .lastCycleAt(lastCycleAt)
                .build();
    }
}
