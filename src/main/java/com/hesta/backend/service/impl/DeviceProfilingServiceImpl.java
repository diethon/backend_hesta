package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.DeviceUptimeResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.service.DeviceProfilingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceProfilingServiceImpl implements DeviceProfilingService {

    private final DeviceRepository deviceRepository;
    private final DeviceStateHistoryRepository historyRepository;

    @Override
    @Transactional(readOnly = true)
    public DeviceUptimeResponse getDeviceUptime(UUID deviceId, int days) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        int effectiveDays = days > 0 ? days : 7;
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusDays(effectiveDays);
        double totalHours = effectiveDays * 24.0;

        List<DeviceStateHistory> histories = historyRepository
                .findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(deviceId, from, to);

        List<DeviceUptimeResponse.TimelinePoint> timelinePoints = new ArrayList<>();
        DateTimeFormatter dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

        double onSeconds = 0.0;
        double offSeconds = 0.0;

        if (histories.isEmpty()) {
            boolean isCurrentlyOn = isStateOn(device.getCurrentState());
            long totalWindowSeconds = effectiveDays * 24L * 3600L;
            if (isCurrentlyOn) {
                onSeconds = totalWindowSeconds;
            } else {
                offSeconds = totalWindowSeconds;
            }
            timelinePoints.add(DeviceUptimeResponse.TimelinePoint.builder()
                    .timestamp(from.format(dtf))
                    .state(isCurrentlyOn ? "ON" : "OFF")
                    .durationSeconds(totalWindowSeconds)
                    .build());
        } else {
            OffsetDateTime cursor = from;
            boolean currentState = isStateOn(histories.get(0).getPreviousState());

            for (DeviceStateHistory h : histories) {
                OffsetDateTime changeTime = h.getChangedAt();
                if (changeTime.isAfter(cursor)) {
                    long sec = Duration.between(cursor, changeTime).getSeconds();
                    if (currentState) {
                        onSeconds += sec;
                    } else {
                        offSeconds += sec;
                    }
                    timelinePoints.add(DeviceUptimeResponse.TimelinePoint.builder()
                            .timestamp(cursor.format(dtf))
                            .state(currentState ? "ON" : "OFF")
                            .durationSeconds(sec)
                            .build());
                    cursor = changeTime;
                }
                currentState = isStateOn(h.getNewState());
            }

            if (cursor.isBefore(to)) {
                long sec = Duration.between(cursor, to).getSeconds();
                if (currentState) {
                    onSeconds += sec;
                } else {
                    offSeconds += sec;
                }
                timelinePoints.add(DeviceUptimeResponse.TimelinePoint.builder()
                        .timestamp(cursor.format(dtf))
                        .state(currentState ? "ON" : "OFF")
                        .durationSeconds(sec)
                        .build());
            }
        }

        double onHours = Math.round((onSeconds / 3600.0) * 100.0) / 100.0;
        double offHours = Math.round((offSeconds / 3600.0) * 100.0) / 100.0;
        double uptimePercent = totalHours > 0 ? Math.round(((onHours / totalHours) * 100.0) * 10.0) / 10.0 : 0.0;

        return DeviceUptimeResponse.builder()
                .uptimePercent(uptimePercent)
                .totalHours(totalHours)
                .onHours(onHours)
                .offHours(offHours)
                .timelinePoints(timelinePoints)
                .build();
    }

    private boolean isStateOn(Map<String, Object> state) {
        if (state == null || state.isEmpty()) return false;
        Object power = state.get("power");
        if (power != null) {
            if (power instanceof Boolean b) return b;
            String pStr = power.toString().trim().toUpperCase();
            if ("ON".equals(pStr) || "TRUE".equals(pStr) || "1".equals(pStr)) return true;
        }
        Object status = state.get("status");
        if (status != null) {
            String sStr = status.toString().trim().toUpperCase();
            if ("ON".equals(sStr) || "OPEN".equals(sStr) || "ACTIVE".equals(sStr)) return true;
        }
        Object stateVal = state.get("state");
        if (stateVal != null) {
            if (stateVal instanceof Boolean b) return b;
            String sStr = stateVal.toString().trim().toUpperCase();
            if ("ON".equals(sStr) || "TRUE".equals(sStr) || "OPEN".equals(sStr)) return true;
        }
        return false;
    }
}
